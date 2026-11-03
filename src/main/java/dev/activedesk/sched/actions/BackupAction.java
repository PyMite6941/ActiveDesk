package dev.activedesk.sched.actions;

import dev.activedesk.sched.TaskAction;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Zips a file/folder to dest/name-yyyyMMdd-HHmmss.zip and keeps the newest N. params: source, dest, keep, name. */
public final class BackupAction implements TaskAction {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Override
    public String run(Context ctx) throws Exception {
        String src = ctx.task().param("source");
        String dst = ctx.task().param("dest");
        if (src == null || dst == null) throw new IllegalArgumentException("backup task needs params.source and params.dest");
        Path source = Path.of(expand(src)).toAbsolutePath().normalize();
        Path dest = Path.of(expand(dst)).toAbsolutePath().normalize();
        if (!Files.exists(source)) throw new IllegalArgumentException("source does not exist: " + source);
        if (Files.isDirectory(source) && dest.startsWith(source))
            throw new IllegalArgumentException("dest is inside source; the backup would include itself");
        int keep = Integer.parseInt(ctx.task().param("keep", "7").trim());
        String name = ctx.task().param("name", source.getFileName() == null ? "backup" : source.getFileName().toString());
        if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("backup name must be [A-Za-z0-9_.-]+");

        Files.createDirectories(dest);
        Path out = dest.resolve(name + "-" + LocalDateTime.now().format(TS) + ".zip");
        Path part = dest.resolve(out.getFileName() + ".part");
        long[] stats = new long[3]; // files, bytes, skipped
        try (OutputStream os = Files.newOutputStream(part); ZipOutputStream zip = new ZipOutputStream(os)) {
            Path base = Files.isDirectory(source) ? source : source.getParent();
            List<Path> files;
            try (Stream<Path> s = Files.isDirectory(source) ? Files.walk(source) : Stream.of(source)) {
                files = s.filter(Files::isRegularFile).toList();
            }
            for (Path f : files) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("backup cancelled");
                String entry = base.relativize(f).toString().replace('\\', '/');
                try (InputStream in = Files.newInputStream(f)) {
                    zip.putNextEntry(new ZipEntry(entry));
                    stats[1] += in.transferTo(zip);
                    zip.closeEntry();
                    stats[0]++;
                } catch (IOException e) {
                    stats[2]++; // locked / unreadable file
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        int pruned = 0;
        List<Path> old = new ArrayList<>();
        try (Stream<Path> s = Files.list(dest)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(name + "-") && n.endsWith(".zip");
            }).sorted().forEach(old::add);
        }
        for (int i = 0; i < old.size() - Math.max(1, keep); i++) {
            Files.deleteIfExists(old.get(i));
            pruned++;
        }
        return "wrote " + out + " (" + stats[0] + " files, " + stats[1] + " bytes, " + stats[2] + " skipped, pruned " + pruned + ")";
    }

    static String expand(String p) {
        return p.startsWith("~") ? System.getProperty("user.home") + p.substring(1) : p;
    }
}
