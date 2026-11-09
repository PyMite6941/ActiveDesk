package dev.activedesk.remote;

import dev.activedesk.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Windows text entry via SendInput + KEYEVENTF_UNICODE, run by one long-lived PowerShell helper.
 *
 * Why not java.awt.Robot: Robot can only press virtual keys, so "A" or "!" needs Shift held around the
 * key. Modern Windows apps (Windows 11 Notepad, WinUI, Electron, browsers) read the live modifier
 * state, so under load Shift is seen too early or too late and "Desk" arrives as "desk", "123" as
 * "!@#". Unicode input carries the character itself: no modifiers, no clipboard, any keyboard layout
 * and any language. Pure Java can't call SendInput, hence the helper (PowerShell ships with Windows).
 * If it can't start, {@link #type} returns false and the caller falls back to Robot.
 */
final class WinTyper {
    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            Add-Type -TypeDefinition @'
            using System; using System.Runtime.InteropServices; using System.Threading;
            public static class AdType {
              [StructLayout(LayoutKind.Sequential)] struct MI { public int dx, dy; public uint data, flags, time; public IntPtr extra; }
              [StructLayout(LayoutKind.Sequential)] struct KI { public ushort vk, scan; public uint flags, time; public IntPtr extra; }
              [StructLayout(LayoutKind.Explicit)] struct UN { [FieldOffset(0)] public MI mi; [FieldOffset(0)] public KI ki; }
              [StructLayout(LayoutKind.Sequential)] struct IN { public uint type; public UN u; }
              [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, IN[] i, int size);
              static void Key(ushort vk, ushort scan, uint flags) {
                IN[] a = new IN[2];
                a[0].type = 1; a[0].u.ki.vk = vk; a[0].u.ki.scan = scan; a[0].u.ki.flags = flags;
                a[1] = a[0]; a[1].u.ki.flags = flags | 2;
                if (SendInput(2, a, Marshal.SizeOf(typeof(IN))) != 2) throw new Exception("SendInput blocked");
                Thread.Sleep(25);  // Windows 11 Notepad garbles faster input
              }
              public static void Type(string s) {
                foreach (char c in s) {
                  if (c == '\\r') continue;
                  if (c == '\\n') Key(0x0D, 0, 0);
                  else if (c == '\\t') Key(0x09, 0, 0);
                  else Key(0, c, 4);
                }
              }
            }
            '@
            [Console]::Out.WriteLine('ready'); [Console]::Out.Flush()
            while ($null -ne ($l = [Console]::In.ReadLine())) {
              try { [AdType]::Type([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($l))); $r = 'ok' }
              catch { $r = 'err ' + $_.Exception.Message }
              [Console]::Out.WriteLine($r); [Console]::Out.Flush()
            }
            """;

    private Process proc;
    private BufferedReader out;
    private OutputStream in;
    private boolean broken;

    /** True if the text was typed; false means "use another method" (helper unavailable or refused). */
    synchronized boolean type(String text) {
        if (broken) return false;
        try {
            if (proc == null || !proc.isAlive()) start();
            in.write((Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\n").getBytes(StandardCharsets.US_ASCII));
            in.flush();
            String r = readLine(10 + text.length() / 20); // ~25 ms per character
            if ("ok".equals(r)) return true;
            Log.info("unicode typing failed (" + r + "), using key presses");
            return false; // e.g. an elevated window is focused: Robot would be blocked too, but let it try
        } catch (IOException e) {
            Log.info("unicode typing unavailable, using key presses: " + e.getMessage());
            broken = true;
            close();
            return false;
        }
    }

    private void start() throws IOException {
        String enc = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", enc).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        in = proc.getOutputStream();
        String r = readLine(30);
        if (!"ready".equals(r)) throw new IOException("helper did not start (" + r + ")");
    }

    private String readLine(int seconds) throws IOException {
        try {
            String r = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (IOException e) {
                    return null;
                }
            }).get(seconds, TimeUnit.SECONDS);
            if (r == null) throw new IOException("helper exited");
            return r.trim();
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("helper timed out");
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IOException(e.toString());
        }
    }

    synchronized void close() {
        if (proc != null) proc.destroy();
        proc = null;
    }
}
