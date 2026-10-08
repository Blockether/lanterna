/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010-2024 Martin Berglund
 */
package com.googlecode.lanterna.terminal.ansi;

import com.googlecode.lanterna.UnicodeWidth;
import com.googlecode.lanterna.UnicodeWidth.WidthMethod;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects how the terminal measures and draws wide text. This is a port of the width rules in the opentui
 * {@code Terminal} (packages/native/src/terminal.zig): the same environment variables, the same queries and the
 * same parsing of the terminal replies. Feed every terminal reply to {@link #processCapabilityResponse(String)},
 * in the order of arrival.
 * <p>
 * The environment variables {@code OPENTUI_FORCE_WCWIDTH}, {@code OPENTUI_FORCE_UNICODE},
 * {@code OPENTUI_FORCE_NOZWJ} and {@code OPENTUI_FORCE_EXPLICIT_WIDTH} override the detection, as in opentui.
 */
public final class TerminalCapabilities {

    /** The terminal multiplexer between the application and the terminal. */
    public enum Multiplexer {
        NONE,
        TMUX,
        ZELLIJ,
        SCREEN
    }

    /** Queries the terminal version (XTVERSION). */
    static final String XTVERSION = "\033[>0q";
    static final String HIDE_CURSOR = "\033[?25l";
    static final String SAVE_CURSOR_STATE = "\033[s";
    static final String RESTORE_CURSOR_STATE = "\033[u";
    static final String CURSOR_POSITION_REQUEST = "\033[6n";
    /** Asks if the terminal supports grapheme cluster mode 2027 (DECRQM). */
    static final String UNICODE_QUERY = "\033[?2027$p";
    static final String HOME = "\033[H";
    static final String EXPLICIT_WIDTH_QUERY = "\033]66;w=1; \033\\";
    static final String SCALED_TEXT_QUERY = "\033]66;s=2; \033\\";
    /** Turns on grapheme cluster mode 2027. */
    public static final String UNICODE_SET = "\033[?2027h";

    private static final int NAME_LENGTH = 64;
    private static final int VERSION_LENGTH = 32;

    private final Map<String, String> env;

    private WidthMethod unicode = WidthMethod.UNICODE;
    private boolean explicitWidth = false;
    private boolean scaledText = false;
    private boolean explicitCursorPositioning = false;
    private boolean remote = false;
    private Multiplexer multiplexer = Multiplexer.NONE;
    private String terminalName = "";
    private String terminalVersion = "";
    private boolean fromXtversion = false;
    private boolean skipExplicitWidthQuery = false;
    private boolean startupCursorQueryPending = false;
    private int explicitWidthProbeReportsPending = 0;
    private Boolean unicodeWideLocked = null;

    /** Creates the capabilities for the environment of this process. */
    public TerminalCapabilities() {
        this(System.getenv());
    }

    /**
     * Creates the capabilities for an explicit environment.
     * @param env Environment variables of the terminal session
     */
    public TerminalCapabilities(Map<String, String> env) {
        this.env = Collections.unmodifiableMap(new HashMap<>(env));
    }

    /** @return Width method for the screen buffer: {@code NO_ZWJ} gives {@code UNICODE}, as in opentui */
    public synchronized WidthMethod getRenderWidthMethod() {
        return unicode == WidthMethod.NO_ZWJ ? WidthMethod.UNICODE : unicode;
    }

    /** @return Detected width method of the terminal */
    public synchronized WidthMethod getUnicode() {
        return unicode;
    }

    /** @return {@code true} if the terminal draws text with OSC 66 explicit widths */
    public synchronized boolean isExplicitWidth() {
        return explicitWidth;
    }

    /** @return {@code true} if the terminal supports OSC 66 scaled text */
    public synchronized boolean isScaledText() {
        return scaledText;
    }

    /** @return {@code true} if the renderer must move the cursor after each grapheme */
    public synchronized boolean isExplicitCursorPositioning() {
        return explicitCursorPositioning;
    }

    /** @return {@code true} if the session runs over SSH or mosh */
    public synchronized boolean isRemote() {
        return remote;
    }

    /** @return Detected multiplexer */
    public synchronized Multiplexer getMultiplexer() {
        return multiplexer;
    }

    /** @return Terminal name from the XTVERSION reply or the environment, or an empty string */
    public synchronized String getTerminalName() {
        return terminalName;
    }

    /** @return Terminal version from the XTVERSION reply or the environment, or an empty string */
    public synchronized String getTerminalVersion() {
        return terminalVersion;
    }

    /** @return {@code true} if the terminal answered the XTVERSION query */
    public synchronized boolean isFromXtversion() {
        return fromXtversion;
    }

    /** @return {@code true} while a query still waits for cursor position reports */
    public synchronized boolean isAwaitingCursorPositionReports() {
        return startupCursorQueryPending || explicitWidthProbeReportsPending > 0;
    }

    /**
     * @return {@code true} if the renderer must turn on grapheme cluster mode 2027 ({@link #UNICODE_SET})
     */
    public synchronized boolean isUnicodeModeWanted() {
        return (unicode == WidthMethod.UNICODE || unicode == WidthMethod.UNICODE_WIDE) && !explicitWidth;
    }

    /** Applies the global width method of {@link UnicodeWidth} for these capabilities. */
    public void applyWidthMethod() {
        UnicodeWidth.setWidthMethod(getRenderWidthMethod());
    }

    /**
     * Builds the capability queries (opentui {@code queryTerminalSend}). Send the result to the terminal before the
     * application draws. The explicit width probes write a space at the top-left cell of the current screen.
     * @return Escape sequences to write to the terminal
     */
    public synchronized String buildQuery() {
        checkEnvironmentOverrides();
        unicodeWideLocked = unicode == WidthMethod.UNICODE_WIDE;
        startupCursorQueryPending = true;

        StringBuilder query = new StringBuilder();
        query.append(XTVERSION).append(HIDE_CURSOR).append(SAVE_CURSOR_STATE);
        query.append(CURSOR_POSITION_REQUEST);
        query.append(UNICODE_QUERY);
        if(!skipExplicitWidthQuery) {
            explicitWidthProbeReportsPending = 2;
            query.append(HOME).append(EXPLICIT_WIDTH_QUERY).append(CURSOR_POSITION_REQUEST)
                    .append(HOME).append(SCALED_TEXT_QUERY).append(CURSOR_POSITION_REQUEST);
        }
        else {
            explicitWidthProbeReportsPending = 0;
        }
        query.append(RESTORE_CURSOR_STATE);
        return query.toString();
    }

    /**
     * Applies the environment rules again (opentui {@code enableDetectedFeatures}).
     * @return {@link #UNICODE_SET} if the terminal must use grapheme cluster mode, else an empty string
     */
    public synchronized String enableDetectedFeatures() {
        checkEnvironmentOverrides();
        return isUnicodeModeWanted() ? UNICODE_SET : "";
    }

    /** Applies the environment rules (opentui {@code checkEnvironmentOverrides}). */
    public synchronized void checkEnvironmentOverrides() {
        if(fromXtversion && terminalName.equals("tmux")) {
            multiplexer = Multiplexer.TMUX;
        }
        else if(fromXtversion && terminalName.equalsIgnoreCase("Zellij")) {
            multiplexer = Multiplexer.ZELLIJ;
        }
        else {
            multiplexer = Multiplexer.NONE;
        }
        skipExplicitWidthQuery = false;

        remote = remote || isRemoteSessionEnv();
        applyKnownUnicodeWidthIdentity();
        if(remote) {
            // opentui forwards the host environment and ignores it for a remote session
            return;
        }

        if(!fromXtversion) {
            if(env.containsKey("TMUX")) {
                setMultiplexerWithWcwidth(Multiplexer.TMUX);
            }
            else if(env.containsKey("ZELLIJ") || env.containsKey("ZELLIJ_SESSION_NAME")
                    || env.containsKey("ZELLIJ_PANE_ID")) {
                multiplexer = Multiplexer.ZELLIJ;
                if(terminalName.isEmpty()) {
                    terminalName = "Zellij";
                }
            }
            else if(env.containsKey("STY")) {
                setMultiplexerWithWcwidth(Multiplexer.SCREEN);
            }
            else if(env.containsKey("TERM")) {
                String term = env.get("TERM");
                if(term.startsWith("tmux")) {
                    setMultiplexerWithWcwidth(Multiplexer.TMUX);
                }
                else if(term.startsWith("screen")) {
                    setMultiplexerWithWcwidth(Multiplexer.SCREEN);
                }
                if(term.contains("alacritty")) {
                    explicitCursorPositioning = true;
                }
            }

            String program = env.get("TERM_PROGRAM");
            if(program != null) {
                if(multiplexer != Multiplexer.ZELLIJ) {
                    terminalName = truncate(program, NAME_LENGTH);
                }
                if(multiplexer != Multiplexer.ZELLIJ && program.equals("tmux")) {
                    setMultiplexerWithWcwidth(Multiplexer.TMUX);
                }
                String programVersion = env.get("TERM_PROGRAM_VERSION");
                if(multiplexer != Multiplexer.ZELLIJ && programVersion != null) {
                    terminalVersion = truncate(programVersion, VERSION_LENGTH);
                }
                if(program.equals("vscode")) {
                    unicode = WidthMethod.UNICODE;
                }
                else if(program.equals("Apple_Terminal")) {
                    unicode = WidthMethod.WCWIDTH;
                }
                else if(program.equals("Alacritty")) {
                    explicitCursorPositioning = true;
                }
            }
            if(env.containsKey("ALACRITTY_SOCKET") || env.containsKey("ALACRITTY_LOG")) {
                explicitCursorPositioning = true;
                if(terminalName.isEmpty()) {
                    terminalName = "Alacritty";
                }
            }
            if(env.containsKey("TERMUX_VERSION") || env.containsKey("VHS_RECORD")) {
                unicode = WidthMethod.WCWIDTH;
            }
        }

        applyKnownUnicodeWidthIdentity();

        if(env.containsKey("OPENTUI_FORCE_WCWIDTH")) {
            unicode = WidthMethod.WCWIDTH;
        }
        if(env.containsKey("OPENTUI_FORCE_UNICODE")) {
            unicode = WidthMethod.UNICODE;
        }
        if(env.containsKey("OPENTUI_FORCE_NOZWJ")) {
            unicode = WidthMethod.NO_ZWJ;
        }
        String forceExplicitWidth = env.get("OPENTUI_FORCE_EXPLICIT_WIDTH");
        if("true".equals(forceExplicitWidth) || "1".equals(forceExplicitWidth)) {
            explicitWidth = true;
        }
        else if("false".equals(forceExplicitWidth) || "0".equals(forceExplicitWidth)) {
            explicitWidth = false;
            skipExplicitWidthQuery = true;
        }
    }

    private void setMultiplexerWithWcwidth(Multiplexer detected) {
        multiplexer = detected;
        unicode = WidthMethod.WCWIDTH;
        explicitCursorPositioning = true;
    }

    private boolean isRemoteSessionEnv() {
        return env.containsKey("SSH_CONNECTION") || env.containsKey("SSH_CLIENT") || env.containsKey("SSH_TTY")
                || env.containsKey("MOSH_CONNECTION");
    }

    private void applyKnownUnicodeWidthIdentity() {
        if(unicodeWideLocked != null) {
            if(unicodeWideLocked) {
                unicode = WidthMethod.UNICODE_WIDE;
            }
            return;
        }
        if(unicode == WidthMethod.UNICODE_WIDE) {
            unicode = WidthMethod.UNICODE;
        }
        if(remote || multiplexer != Multiplexer.NONE) {
            return;
        }
        String program = env.get("TERM_PROGRAM");
        String version = env.get("TERM_PROGRAM_VERSION");
        if(program == null || !program.equalsIgnoreCase("ghostty") || version == null
                || !ghosttyWideGraphemeWidths(version)) {
            return;
        }
        if(fromXtversion && !terminalName.equalsIgnoreCase("ghostty")) {
            return;
        }
        unicode = WidthMethod.UNICODE_WIDE;
    }

    /**
     * Ghostty 1.3 and the tip builds from 2026-02-24 measure graphemes with the wide rules.
     * @param version Value of {@code TERM_PROGRAM_VERSION}
     * @return {@code true} if this Ghostty version uses the wide grapheme widths
     */
    static boolean ghosttyWideGraphemeWidths(String version) {
        if(semanticVersionAtLeast(version, 1, 3)) {
            return true;
        }
        String prefix = "0.0.0-";
        if(!version.startsWith(prefix) || version.length() <= prefix.length() + 8
                || version.charAt(prefix.length() + 8) != '.') {
            return false;
        }
        String date = version.substring(prefix.length(), prefix.length() + 8);
        return isDigits(date) && Long.parseLong(date) >= 20260224L;
    }

    static boolean semanticVersionAtLeast(String version, long requiredMajor, long requiredMinor) {
        int suffixStart = version.indexOf('-');
        String core = suffixStart >= 0 ? version.substring(0, suffixStart) : version;
        String[] parts = core.split("\\.", -1);
        if(parts.length != 3) {
            return false;
        }
        long major = parseUnsigned(parts[0]);
        long minor = parseUnsigned(parts[1]);
        long patch = parseUnsigned(parts[2]);
        if(major < 0 || minor < 0 || patch < 0) {
            return false;
        }
        if(major > requiredMajor || (major == requiredMajor && minor > requiredMinor)) {
            return true;
        }
        if(major != requiredMajor || minor != requiredMinor) {
            return false;
        }
        if(patch > 0 || suffixStart < 0) {
            return true;
        }
        String[] suffixParts = version.substring(suffixStart + 1).split("-", -1);
        if(suffixParts.length != 2 || suffixParts[0].isEmpty() || suffixParts[1].length() < 2
                || suffixParts[1].charAt(0) != 'g' || parseUnsigned(suffixParts[0]) < 0) {
            return false;
        }
        for(char c: suffixParts[1].substring(1).toCharArray()) {
            if(!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a decimal {@code u32} like Zig {@code std.fmt.parseInt(u32, text, 10)}: one optional sign, and
     * underscores between digits.
     * @return The value, or -1 if Zig rejects the text
     */
    static long parseUnsigned(String text) {
        return parseUnsigned(text, 0xFFFFFFFFL);
    }

    private static long parseUnsigned(String text, long max) {
        boolean negative = false;
        if(!text.isEmpty() && (text.charAt(0) == '+' || text.charAt(0) == '-')) {
            negative = text.charAt(0) == '-';
            text = text.substring(1);
        }
        if(text.isEmpty() || text.charAt(0) == '_' || text.charAt(text.length() - 1) == '_') {
            return -1;
        }
        long value = 0;
        for(int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if(c == '_') {
                continue;
            }
            if(c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
            if(value > max || (negative && value > 0)) {
                return -1;
            }
        }
        return value;
    }

    private static boolean isDigits(String text) {
        for(int i = 0; i < text.length(); i++) {
            if(text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Processes one terminal reply (opentui {@code processCapabilityResponse}).
     * @param response Raw reply, for example {@code ESC[?2027;2$y}, {@code ESC[1;2R} or {@code ESC P>|kitty(0.40.1)ESC\}
     */
    public synchronized void processCapabilityResponse(String response) {
        if(response.contains("2027;2$y")) {
            unicode = WidthMethod.UNICODE;
        }

        int scan = 0;
        while(scan < response.length()) {
            int esc = response.indexOf("\033[", scan);
            if(esc < 0) {
                break;
            }
            int pos = esc + 2;
            int rowStart = pos;
            pos = skipDigits(response, pos);
            if(pos == rowStart || pos >= response.length() || response.charAt(pos) != ';') {
                scan = esc + 2;
                continue;
            }
            long row = parseUnsigned(response.substring(rowStart, pos), 0xFFFFL);
            if(row < 0) {
                scan = pos + 1;
                continue;
            }
            pos++;
            int columnStart = pos;
            pos = skipDigits(response, pos);
            if(pos == columnStart || pos >= response.length() || response.charAt(pos) != 'R') {
                scan = columnStart;
                continue;
            }
            long column = parseUnsigned(response.substring(columnStart, pos), 0xFFFFL);
            if(column < 0) {
                scan = pos + 1;
                continue;
            }
            processCursorPositionReport(row, column);
            scan = pos + 1;
        }

        int xtversion = response.indexOf("\033P>|");
        if(xtversion >= 0) {
            int start = xtversion + 4;
            int end = response.indexOf("\033\\", start);
            if(end >= 0) {
                parseXtversion(response.substring(start, end));
            }
        }

        if(fromXtversion && terminalName.equalsIgnoreCase("kitty")) {
            unicode = WidthMethod.UNICODE;
        }
        if(response.contains("tmux")) {
            unicode = WidthMethod.WCWIDTH;
            explicitCursorPositioning = true;
        }
        if(response.contains("alacritty")) {
            explicitCursorPositioning = true;
        }
    }

    private static int skipDigits(String text, int pos) {
        while(pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
            pos++;
        }
        return pos;
    }

    private void processCursorPositionReport(long row, long column) {
        if(startupCursorQueryPending && row >= 1 && column >= 1) {
            startupCursorQueryPending = false;
        }
        else if(explicitWidthProbeReportsPending > 0) {
            int probeIndex = 2 - explicitWidthProbeReportsPending;
            explicitWidthProbeReportsPending--;
            if(row == 1 && probeIndex == 0 && column == 2) {
                explicitWidth = true;
            }
            else if(row == 1 && probeIndex == 1 && column == 3) {
                explicitWidth = true;
                scaledText = true;
            }
        }
    }

    private void parseXtversion(String text) {
        if(text.isEmpty()) {
            return;
        }
        int paren = text.indexOf('(');
        if(paren >= 0) {
            terminalName = truncate(text.substring(0, paren), NAME_LENGTH);
            int close = text.indexOf(')', paren);
            terminalVersion = close >= 0 ? truncate(text.substring(paren + 1, close), VERSION_LENGTH) : "";
        }
        else {
            int space = text.indexOf(' ');
            if(space >= 0) {
                terminalName = truncate(text.substring(0, space), NAME_LENGTH);
                terminalVersion = truncate(text.substring(space + 1), VERSION_LENGTH);
            }
            else {
                terminalName = truncate(text, NAME_LENGTH);
                terminalVersion = "";
            }
        }
        fromXtversion = true;
        if(terminalName.equals("tmux")) {
            multiplexer = Multiplexer.TMUX;
        }
        else if(terminalName.equalsIgnoreCase("Zellij")) {
            multiplexer = Multiplexer.ZELLIJ;
        }
        else {
            multiplexer = Multiplexer.NONE;
        }
    }

    private static String truncate(String text, int length) {
        return text.length() > length ? text.substring(0, length) : text;
    }
}
