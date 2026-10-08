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
import com.googlecode.lanterna.terminal.ansi.TerminalCapabilities.Multiplexer;
import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TerminalCapabilitiesTest {

    private static final String QUERY_WITH_PROBES = "\033[>0q\033[?25l\033[s\033[6n\033[?2027$p"
            + "\033[H\033]66;w=1; \033\\\033[6n\033[H\033]66;s=2; \033\\\033[6n\033[u";

    @After
    public void restoreWidthMethod() {
        UnicodeWidth.setWidthMethod(WidthMethod.UNICODE);
    }

    private static Map<String, String> env(String... pairs) {
        Map<String, String> env = new HashMap<>();
        for(int i = 0; i < pairs.length; i += 2) {
            env.put(pairs[i], pairs[i + 1]);
        }
        return env;
    }

    private static TerminalCapabilities detect(Map<String, String> env, String... responses) {
        TerminalCapabilities capabilities = new TerminalCapabilities(env);
        capabilities.buildQuery();
        capabilities.enableDetectedFeatures();
        for(String response: responses) {
            capabilities.processCapabilityResponse(response);
            capabilities.enableDetectedFeatures();
        }
        return capabilities;
    }

    private static String unescape(String text) {
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if(c == '\\') {
                char next = text.charAt(++i);
                builder.append(next == 'e' ? '\033' : next);
            }
            else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static int widthCode(WidthMethod method) {
        return method == WidthMethod.WCWIDTH ? 0 : method == WidthMethod.UNICODE_WIDE ? 3 : 1;
    }

    /**
     * Replays scenarios that the opentui native library (libopentui) answered. Each line has the environment, the
     * terminal replies and the state that opentui reported after the replies.
     */
    @Test
    public void matchesOpentuiGoldenScenarios() throws IOException {
        InputStream stream = getClass().getResourceAsStream("/com/googlecode/lanterna/opentui-terminal-capabilities.txt");
        assertNotNull(stream);
        int scenarios = 0;
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                String[] columns = line.split("\t", -1);
                Map<String, String> env = new HashMap<>();
                if(!columns[0].isEmpty()) {
                    for(String pair: columns[0].split(" & ")) {
                        int equals = pair.indexOf('=');
                        env.put(unescape(pair.substring(0, equals)), unescape(pair.substring(equals + 1)));
                    }
                }
                String[] responses = columns[1].isEmpty() ? new String[0] : columns[1].split(" , ");
                for(int i = 0; i < responses.length; i++) {
                    responses[i] = unescape(responses[i]);
                }
                TerminalCapabilities capabilities = detect(env, responses);
                String actual = "u=" + widthCode(capabilities.getUnicode())
                        + " ew=" + capabilities.isExplicitWidth()
                        + " st=" + capabilities.isScaledText()
                        + " ecp=" + capabilities.isExplicitCursorPositioning()
                        + " remote=" + capabilities.isRemote()
                        + " mux=" + capabilities.getMultiplexer().ordinal()
                        + " name=" + unescape(capabilities.getTerminalName())
                        + " ver=" + unescape(capabilities.getTerminalVersion())
                        + " xt=" + capabilities.isFromXtversion();
                assertEquals(line, unescape(columns[2]), actual);
                scenarios++;
            }
        }
        assertEquals(2000, scenarios);
    }

    @Test
    public void buildsTheOpentuiQuery() {
        TerminalCapabilities capabilities = new TerminalCapabilities(Collections.<String, String>emptyMap());
        assertFalse(capabilities.isAwaitingCursorPositionReports());
        assertEquals(QUERY_WITH_PROBES, capabilities.buildQuery());
        assertTrue(capabilities.isAwaitingCursorPositionReports());
        capabilities.processCapabilityResponse("\033[5;7R");
        assertTrue(capabilities.isAwaitingCursorPositionReports());
        capabilities.processCapabilityResponse("\033[1;2R");
        assertTrue(capabilities.isAwaitingCursorPositionReports());
        capabilities.processCapabilityResponse("\033[1;3R");
        assertFalse(capabilities.isAwaitingCursorPositionReports());
        assertTrue(capabilities.isExplicitWidth());
        assertTrue(capabilities.isScaledText());
        assertEquals("", capabilities.enableDetectedFeatures());
    }

    @Test
    public void skipsTheWidthProbesWhenExplicitWidthIsOff() {
        for(String value: new String[] {"0", "false"}) {
            TerminalCapabilities capabilities = new TerminalCapabilities(env("OPENTUI_FORCE_EXPLICIT_WIDTH", value));
            assertEquals("\033[>0q\033[?25l\033[s\033[6n\033[?2027$p\033[u", capabilities.buildQuery());
            capabilities.processCapabilityResponse("\033[3;3R");
            assertFalse(capabilities.isAwaitingCursorPositionReports());
            capabilities.processCapabilityResponse("\033[1;2R");
            assertFalse(capabilities.isExplicitWidth());
        }
        for(String value: new String[] {"1", "true"}) {
            TerminalCapabilities capabilities = new TerminalCapabilities(env("OPENTUI_FORCE_EXPLICIT_WIDTH", value));
            assertEquals(QUERY_WITH_PROBES, capabilities.buildQuery());
            assertTrue(capabilities.isExplicitWidth());
        }
    }

    @Test
    public void ignoresCursorReportsWithoutAQuery() {
        TerminalCapabilities capabilities = new TerminalCapabilities(Collections.<String, String>emptyMap());
        capabilities.processCapabilityResponse("\033[1;2R");
        assertFalse(capabilities.isExplicitWidth());
        assertFalse(capabilities.isAwaitingCursorPositionReports());
    }

    @Test
    public void readsCursorReportsInsideOneReply() {
        TerminalCapabilities capabilities = new TerminalCapabilities(Collections.<String, String>emptyMap());
        capabilities.buildQuery();
        capabilities.processCapabilityResponse("\033[2;1Rx\033[\033[;\033[1\033[1;\033[1;x\033[1_;2R"
                + "\033[70000;1R\033[1;70000R\033[1;2R\033[1;3R");
        assertTrue(capabilities.isExplicitWidth());
        assertTrue(capabilities.isScaledText());

        capabilities = new TerminalCapabilities(Collections.<String, String>emptyMap());
        capabilities.buildQuery();
        capabilities.processCapabilityResponse("\033[0;0R\033[2;1R\033[1;3R");
        assertTrue(capabilities.isExplicitWidth());
        assertTrue(capabilities.isScaledText());
        assertFalse(capabilities.isAwaitingCursorPositionReports());

        capabilities = new TerminalCapabilities(Collections.<String, String>emptyMap());
        capabilities.buildQuery();
        capabilities.processCapabilityResponse("\033[12");
        capabilities.processCapabilityResponse("\033[1;2");
        capabilities.processCapabilityResponse("\033[1;0R");
        assertTrue(capabilities.isAwaitingCursorPositionReports());
        assertFalse(capabilities.isExplicitWidth());
        capabilities.processCapabilityResponse("\033[1;1R\033[1;2R");
        assertFalse(capabilities.isExplicitWidth());
        assertFalse(capabilities.isAwaitingCursorPositionReports());
    }

    @Test
    public void rendersNoZwjWithTheUnicodeWidths() {
        TerminalCapabilities capabilities = detect(env("OPENTUI_FORCE_NOZWJ", "1"));
        assertEquals(WidthMethod.NO_ZWJ, capabilities.getUnicode());
        assertEquals(WidthMethod.UNICODE, capabilities.getRenderWidthMethod());
        assertFalse(capabilities.isUnicodeModeWanted());
        assertEquals("", capabilities.enableDetectedFeatures());

        capabilities = detect(env("OPENTUI_FORCE_WCWIDTH", "1"));
        assertEquals(WidthMethod.WCWIDTH, capabilities.getRenderWidthMethod());
        capabilities.applyWidthMethod();
        assertEquals(WidthMethod.WCWIDTH, UnicodeWidth.getWidthMethod());
        assertEquals("", capabilities.enableDetectedFeatures());
    }

    @Test
    public void turnsOnGraphemeModeForUnicodeTerminals() {
        TerminalCapabilities capabilities = detect(Collections.<String, String>emptyMap());
        assertTrue(capabilities.isUnicodeModeWanted());
        assertEquals(TerminalCapabilities.UNICODE_SET, capabilities.enableDetectedFeatures());

        capabilities = detect(env("TERM_PROGRAM", "ghostty", "TERM_PROGRAM_VERSION", "1.3.0"));
        assertEquals(WidthMethod.UNICODE_WIDE, capabilities.getUnicode());
        assertEquals(TerminalCapabilities.UNICODE_SET, capabilities.enableDetectedFeatures());
        capabilities.applyWidthMethod();
        assertEquals(WidthMethod.UNICODE_WIDE, UnicodeWidth.getWidthMethod());
    }

    @Test
    public void readsTheProcessEnvironment() {
        TerminalCapabilities capabilities = new TerminalCapabilities();
        assertEquals(Multiplexer.NONE, capabilities.getMultiplexer());
        assertNotNull(capabilities.buildQuery());
    }

    @Test
    public void truncatesLongNamesAndVersions() {
        StringBuilder longText = new StringBuilder();
        for(int i = 0; i < 70; i++) {
            longText.append('n');
        }
        TerminalCapabilities capabilities = detect(env("TERM_PROGRAM", longText.toString(),
                "TERM_PROGRAM_VERSION", longText.toString()));
        assertEquals(64, capabilities.getTerminalName().length());
        assertEquals(32, capabilities.getTerminalVersion().length());

        capabilities = detect(Collections.<String, String>emptyMap(),
                "\033P>|" + longText + "(" + longText + ")\033\\");
        assertEquals(64, capabilities.getTerminalName().length());
        assertEquals(32, capabilities.getTerminalVersion().length());
        assertTrue(capabilities.isFromXtversion());
    }

    @Test
    public void parsesXtversionVariants() {
        TerminalCapabilities capabilities = detect(Collections.<String, String>emptyMap(), "\033P>|foot(1.2\033\\");
        assertEquals("foot", capabilities.getTerminalName());
        assertEquals("", capabilities.getTerminalVersion());

        capabilities = detect(Collections.<String, String>emptyMap(), "\033P>|\033\\");
        assertFalse(capabilities.isFromXtversion());

        capabilities = detect(Collections.<String, String>emptyMap(), "\033P>|kitty(0.40)");
        assertFalse(capabilities.isFromXtversion());

        capabilities = detect(Collections.<String, String>emptyMap(), "\033P>|tmux 3.4\033\\");
        assertEquals(Multiplexer.TMUX, capabilities.getMultiplexer());
        assertEquals(WidthMethod.WCWIDTH, capabilities.getUnicode());
        assertEquals("3.4", capabilities.getTerminalVersion());

        capabilities = detect(Collections.<String, String>emptyMap(), "\033P>|ZELLIJ\033\\");
        assertEquals(Multiplexer.ZELLIJ, capabilities.getMultiplexer());
    }

    @Test
    public void parsesUnsignedNumbersLikeZig() {
        assertEquals(12, TerminalCapabilities.parseUnsigned("12"));
        assertEquals(12, TerminalCapabilities.parseUnsigned("+1_2"));
        assertEquals(0, TerminalCapabilities.parseUnsigned("-0"));
        assertEquals(0, TerminalCapabilities.parseUnsigned("-0_0"));
        assertEquals(4294967295L, TerminalCapabilities.parseUnsigned("4294967295"));
        assertEquals(-1, TerminalCapabilities.parseUnsigned("4294967296"));
        assertEquals(-1, TerminalCapabilities.parseUnsigned("-1"));
        assertEquals(-1, TerminalCapabilities.parseUnsigned(""));
        assertEquals(-1, TerminalCapabilities.parseUnsigned("+"));
        assertEquals(-1, TerminalCapabilities.parseUnsigned("_1"));
        assertEquals(-1, TerminalCapabilities.parseUnsigned("1_"));
        assertEquals(-1, TerminalCapabilities.parseUnsigned("1a"));
        assertEquals(-1, TerminalCapabilities.parseUnsigned("1/"));
    }

    @Test
    public void comparesSemanticVersionsLikeOpentui() {
        assertTrue(TerminalCapabilities.semanticVersionAtLeast("1.3.0", 1, 3));
        assertTrue(TerminalCapabilities.semanticVersionAtLeast("2.0.0", 1, 3));
        assertTrue(TerminalCapabilities.semanticVersionAtLeast("1.4.0", 1, 3));
        assertTrue(TerminalCapabilities.semanticVersionAtLeast("1.3.1-dev", 1, 3));
        assertTrue(TerminalCapabilities.semanticVersionAtLeast("1.3.0-12-gabcDEF09", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.2.9", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("0.9.0", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("x.3.0", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.x.0", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.x", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-dev", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0--gab", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-1-g", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-1-xab", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-a-gab", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-1-gag", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-1-g/", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-1-g:", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-1-gG", 1, 3));
        assertFalse(TerminalCapabilities.semanticVersionAtLeast("1.3.0-1-g`", 1, 3));
    }

    @Test
    public void detectsGhosttyTipBuilds() {
        assertTrue(TerminalCapabilities.ghosttyWideGraphemeWidths("1.3.0"));
        assertTrue(TerminalCapabilities.ghosttyWideGraphemeWidths("0.0.0-20260224.abc"));
        assertTrue(TerminalCapabilities.ghosttyWideGraphemeWidths("0.0.0-20270101.abc"));
        assertFalse(TerminalCapabilities.ghosttyWideGraphemeWidths("0.0.0-20260223.abc"));
        assertFalse(TerminalCapabilities.ghosttyWideGraphemeWidths("1.2.0"));
        assertFalse(TerminalCapabilities.ghosttyWideGraphemeWidths("0.0.0-20260224"));
        assertFalse(TerminalCapabilities.ghosttyWideGraphemeWidths("0.0.0-20260224x"));
        assertFalse(TerminalCapabilities.ghosttyWideGraphemeWidths("0.0.0-2026022a.x"));
        assertFalse(TerminalCapabilities.ghosttyWideGraphemeWidths("0.0.0-2026022/.x"));
        assertFalse(TerminalCapabilities.ghosttyWideGraphemeWidths("0.0.1-20260224.x"));
    }

    @Test
    public void keepsGhosttyWideOnlyForGhostty() {
        Map<String, String> ghostty = env("TERM_PROGRAM", "Ghostty", "TERM_PROGRAM_VERSION", "1.3.0");
        TerminalCapabilities capabilities = new TerminalCapabilities(ghostty);
        capabilities.processCapabilityResponse("\033P>|xterm(390)\033\\");
        capabilities.checkEnvironmentOverrides();
        assertEquals(WidthMethod.UNICODE, capabilities.getUnicode());

        capabilities = new TerminalCapabilities(ghostty);
        capabilities.processCapabilityResponse("\033P>|ghostty 1.3.0\033\\");
        capabilities.checkEnvironmentOverrides();
        assertEquals(WidthMethod.UNICODE_WIDE, capabilities.getUnicode());

        capabilities = detect(env("TERM_PROGRAM", "ghostty"));
        assertEquals(WidthMethod.UNICODE, capabilities.getUnicode());
        capabilities = detect(env("TERM_PROGRAM", "ghostty", "TERM_PROGRAM_VERSION", "1.2.0"));
        assertEquals(WidthMethod.UNICODE, capabilities.getUnicode());
        capabilities = detect(env("TERM_PROGRAM", "ghostty", "TERM_PROGRAM_VERSION", "1.3.0", "SSH_TTY", "/dev/pts/1"));
        assertTrue(capabilities.isRemote());
        assertEquals(WidthMethod.UNICODE, capabilities.getUnicode());
    }
}
