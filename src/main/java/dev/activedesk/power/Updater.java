package dev.activedesk.power;

import dev.activedesk.Config;
import dev.activedesk.net.Hub;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/**
 * `activedesk update [--check] [--force] [--version vX.Y.Z] [--jar file]`: replaces the installed jar
 * with the latest GitHub release, checksum-verified, and restarts ActiveDesk if it was running.
 *
 *   1. find the newest release (or the one asked for) and stop if this copy is already that version
 *   2. download jar + SHA256SUMS next to the installed jar, verify the hash, and check the new jar runs
 *   3. Windows: if the battery guard in Program Files changed, copy the new one (one admin prompt)
 *   4. swap the jar and restart: Linux/macOS swap in place (a running file can be replaced there);
 *      Windows can't replace a jar a JVM has open, so a detached helper swaps it after this exits
 *
 * Bundled app images (jpackage) are updated by downloading the new bundle instead.
 */
public final class Updater {
    private Updater() { }

    static final String REPO = "PyMite6941/ActiveDesk";

    public static int run(List<String> args) throws IOException, InterruptedException {
        boolean check = false, force = false;
        String version = null, localJar = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--check" -> check = true;
                case "--force" -> force = true;
                case "--version" -> version = i + 1 < args.size() ? args.get(++i) : "";
                case "--jar" -> localJar = i + 1 < args.size() ? args.get(++i) : "";
                default -> { return usage("Unknown option '" + a + "'."); }
            }
        }
        if (version != null && !version.matches("v?\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}")) return usage("--version wants something like v1.0.2");
        if (System.getProperty("jpackage.app-path") != null) {
            System.out.println("This is a bundled app. Download the new bundle from https://github.com/" + REPO + "/releases/latest instead.");
            return 1;
        }
        Path jar;
        try {
            jar = Path.of(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        } catch (Exception e) {
            throw new IOException("can't find the installed jar: " + e.getMessage());
        }
        if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) {
            System.out.println("ActiveDesk isn't running from an installed jar (" + jar + "). Nothing to update.");
            return 1;
        }
        Consumer<String> out = l -> System.out.println("  " + l);
        System.out.println("ActiveDesk " + Hub.VERSION + " at " + jar);

        // 1 + 2: get and verify the new jar
        Path fresh = jar.resolveSibling("activedesk.jar.new");
        Files.deleteIfExists(fresh);
        String newVersion;
        try {
            if (localJar != null) {
                Files.copy(Path.of(localJar), fresh, StandardCopyOption.REPLACE_EXISTING);
            } else {
                String tag = version != null ? (version.startsWith("v") ? version : "v" + version) : latestTag();
                if (!force && compare(tag.substring(1), Hub.VERSION) <= 0) {
                    System.out.println("Already up to date (latest release is " + tag + ").");
                    return 0;
                }
                if (check) {
                    System.out.println("Update available: " + tag + ". Run 'activedesk update' to install it.");
                    return 0;
                }
                String base = "https://github.com/" + REPO + "/releases/download/" + tag + "/";
                out.accept("Downloading " + tag + "...");
                download(base + "activedesk.jar", fresh);
                String want = expectedHash(new String(fetch(base + "SHA256SUMS.txt"), StandardCharsets.UTF_8));
                String got = sha256(fresh);
                if (want == null || !want.equalsIgnoreCase(got)) throw new IOException("checksum mismatch: the download is corrupt or was tampered with");
                out.accept("Checksum OK (" + got + ")");
            }
            newVersion = versionOf(fresh);
            if (newVersion == null) throw new IOException("the new jar doesn't run");
            if (localJar != null && !force && compare(newVersion, Hub.VERSION) <= 0) {
                System.out.println("That jar is " + newVersion + ", not newer than " + Hub.VERSION + ". Add --force to install it anyway.");
                Files.deleteIfExists(fresh);
                return 0;
            }
        } catch (IOException e) {
            Files.deleteIfExists(fresh);
            System.out.println("Update failed: " + e.getMessage() + ". Nothing was changed.");
            return 1;
        }
        if (check) { Files.deleteIfExists(fresh); System.out.println("Would install " + newVersion + "."); return 0; }

        // 3: the Windows battery guard is a copy in Program Files; keep it in step with the jar
        if (Proc.windows()) refreshGuard(fresh, out);

        // 4: swap + restart
        Path home = Config.defaultHome();
        List<String> cp = List.of(jar.toString() + java.io.File.pathSeparator + jar.resolveSibling("lib") + java.io.File.separator + "*");
        if (Proc.windows()) {
            boolean wasRunning = Uninstaller.stopNode(home, out);
            String javaw = javaExe(true);
            StringBuilder s = new StringBuilder();
            s.append("Wait-Process -Id ").append(ProcessHandle.current().pid()).append(" -Timeout 60 -ErrorAction SilentlyContinue\n")
             .append("$ok = $false\n")
             .append("for ($i = 0; $i -lt 30; $i++) { try { Move-Item -LiteralPath ").append(Proc.psq(fresh.toString()))
             .append(" -Destination ").append(Proc.psq(jar.toString())).append(" -Force -ErrorAction Stop; $ok = $true; break } catch { Start-Sleep -Seconds 1 } }\n")
             .append("$log = ").append(Proc.psq(home.resolve("update.log").toString())).append("\n")
             .append("$msg = if ($ok) { 'updated to ").append(newVersion).append("' } else { 'update failed: the old jar stayed locked' }\n")
             .append("try { Add-Content -LiteralPath $log -Value ((Get-Date -Format s) + ' ' + $msg) } catch { }\n");
            if (wasRunning) {
                s.append("Start-Process -FilePath ").append(Proc.psq(javaw)).append(" -WindowStyle Hidden -ArgumentList @('-cp', ")
                 .append(Proc.psq("\"" + cp.get(0) + "\"")).append(", 'dev.activedesk.ActiveDesk', 'run')\n");
            }
            String inner = java.util.Base64.getEncoder().encodeToString(s.toString().getBytes(StandardCharsets.UTF_16LE));
            Proc.Result r = Proc.powershell(20, "Start-Process powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + inner + "'");
            if (!r.ok()) {
                Files.deleteIfExists(fresh);
                System.out.println("Update failed: could not start the helper (" + r.out().trim() + ").");
                return 1;
            }
            System.out.println("Updating to " + newVersion + ": the new version is swapped in a few seconds after this command exits"
                    + (wasRunning ? " and ActiveDesk starts again." : ".") + " Result: " + home.resolve("update.log"));
            return 0;
        }
        Files.move(fresh, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        out.accept("Installed " + newVersion + ".");
        if (Proc.linux() && Proc.run(10, "systemctl", "--user", "is-active", "--quiet", "activedesk").ok()) {
            Proc.Result r = Proc.run(30, "systemctl", "--user", "restart", "activedesk");
            out.accept(r.ok() ? "Restarted the ActiveDesk service." : "Could not restart the service: " + r.out().trim());
        } else if (Proc.mac() && Proc.run(10, "launchctl", "list", "dev.activedesk").ok()) {
            Uninstaller.stopNode(home, out); // launchd (KeepAlive) starts it again, now from the new jar
        } else if (Uninstaller.stopNode(home, out)) {
            List<String> cmd = new ArrayList<>(List.of("nohup", javaExe(false), "-cp", cp.get(0), "dev.activedesk.ActiveDesk", "run"));
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))).start();
            out.accept("Started ActiveDesk again.");
        }
        System.out.println("ActiveDesk is now " + newVersion + ".");
        return 0;
    }

    private static int usage(String why) {
        System.out.println(why + " Usage: activedesk update [--check] [--force] [--version vX.Y.Z] [--jar file]");
        return 2;
    }

    /** Copies the guard script from the new jar into Program Files when it differs (one admin prompt). */
    private static void refreshGuard(Path newJar, Consumer<String> out) throws IOException {
        String pf = System.getenv("ProgramFiles");
        if (pf == null) return;
        Path installed = Path.of(pf, "ActiveDesk", "battery-guard.ps1");
        if (!Files.exists(installed)) return;
        byte[] fresh;
        try (ZipFile z = new ZipFile(newJar.toFile())) {
            var e = z.getEntry("power/battery-guard.ps1");
            if (e == null) return;
            try (InputStream in = z.getInputStream(e)) { fresh = in.readAllBytes(); }
        }
        if (Arrays.equals(fresh, Files.readAllBytes(installed))) return;
        Path tmp = Files.createTempFile("activedesk-battery-guard", ".ps1");
        try {
            Files.write(tmp, fresh);
            out.accept("The battery guard changed in this version. Windows asks for admin permission once to update it...");
            int rc = Proc.elevate("try { Copy-Item -LiteralPath " + Proc.psq(tmp.toString()) + " -Destination (Join-Path $env:ProgramFiles 'ActiveDesk\\battery-guard.ps1') -Force -ErrorAction Stop; exit 0 } catch { exit 1 }", 300);
            out.accept(rc == 0 ? "Battery guard updated."
                    : "Battery guard NOT updated (exit " + rc + "). Run 'activedesk power install' later to update it.");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** "1.0.2" of a jar, by running it, or null. */
    private static String versionOf(Path jar) {
        Proc.Result r = Proc.run(60, javaExe(false), "-cp", jar.toString(), "dev.activedesk.ActiveDesk", "version");
        var m = java.util.regex.Pattern.compile("ActiveDesk (\\d+\\.\\d+\\.\\d+)").matcher(r.out());
        return r.ok() && m.find() ? m.group(1) : null;
    }

    private static String javaExe(boolean windowless) {
        String java = ProcessHandle.current().info().command().orElse("java");
        if (!Proc.windows()) return java;
        Path p = Path.of(java);
        Path w = p.resolveSibling(windowless ? "javaw.exe" : "java.exe");
        return Files.exists(w) ? w.toString() : java;
    }

    /** "v1.0.2" from the redirect github.com/<repo>/releases/latest -> .../tag/v1.0.2 */
    static String latestTag() throws IOException {
        HttpURLConnection c = open("https://github.com/" + REPO + "/releases/latest");
        c.setInstanceFollowRedirects(false);
        try {
            String loc = c.getHeaderField("Location");
            var m = loc == null ? null : java.util.regex.Pattern.compile("/tag/(v\\d+\\.\\d+\\.\\d+)$").matcher(loc);
            if (m == null || !m.find()) throw new IOException("could not find the latest release (HTTP " + c.getResponseCode() + ")");
            return m.group(1);
        } finally {
            c.disconnect();
        }
    }

    static String expectedHash(String sums) {
        for (String line : sums.split("\\R")) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 2 && p[1].replace("*", "").equals("activedesk.jar") && p[0].matches("[0-9a-fA-F]{64}")) return p[0];
        }
        return null;
    }

    /** Numeric x.y.z comparison. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int d = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (d != 0) return d;
        }
        return 0;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "ActiveDesk-updater/" + Hub.VERSION);
        return c;
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream()) { return in.readAllBytes(); }
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, Path to) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream()) { Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

}
