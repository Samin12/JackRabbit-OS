package com.resonolabs.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class SideButtonGestureTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    /** Gradle runs unit tests with the module directory (android/app) as working directory. */
    private static final File APP = new File(".");
    private static final File OVERLAY = new File("../system/power-overlay/src");

    @Test public void onlyTheAliasClassIsAToggle() {
        assertTrue(SideButtonGesture.isToggleClassName("com.resonolabs.voice.SideButtonToggle"));
        assertFalse(SideButtonGesture.isToggleClassName("com.resonolabs.voice.MainActivity"));
        assertFalse(SideButtonGesture.isToggleClassName("com.resonolabs.voice.engineering.SideButtonToggle"));
        assertFalse(SideButtonGesture.isToggleClassName(".SideButtonToggle"));
        assertFalse(SideButtonGesture.isToggleClassName(""));
        assertFalse(SideButtonGesture.isToggleClassName(null));
        assertFalse(SideButtonGesture.isToggle(null));
    }

    @Test public void overlayTargetKeepsTheUnsuffixedClassName() {
        assertEquals("com.resonolabs.voice.engineering/com.resonolabs.voice.SideButtonToggle",
                SideButtonGesture.overlayTargetFor("com.resonolabs.voice.engineering"));
        assertEquals("com.resonolabs.voice/com.resonolabs.voice.SideButtonToggle",
                SideButtonGesture.overlayTargetFor("com.resonolabs.voice"));
    }

    @Test public void restoresOnlyAnOverridingSettingWhenTheOverlayTargetsThisApp() {
        String ours = SideButtonGesture.overlayTargetFor("com.resonolabs.voice.engineering");
        assertFalse("no overlay", SideButtonGesture.shouldRestoreDoublePress(null, ours, "0"));
        assertFalse("stock framework", SideButtonGesture.shouldRestoreDoublePress("", ours, "0"));
        assertFalse("other build type", SideButtonGesture.shouldRestoreDoublePress(
                SideButtonGesture.overlayTargetFor("com.resonolabs.voice"), ours, "0"));
        assertFalse("unset uses the overlay default", SideButtonGesture.shouldRestoreDoublePress(ours, ours, null));
        assertFalse(SideButtonGesture.shouldRestoreDoublePress(ours, ours, "3"));
        assertFalse(SideButtonGesture.shouldRestoreDoublePress(ours, ours, " 3 "));
        assertTrue(SideButtonGesture.shouldRestoreDoublePress(ours, ours, "0"));
        assertTrue(SideButtonGesture.shouldRestoreDoublePress(ours, ours, "2"));
        assertTrue(SideButtonGesture.shouldRestoreDoublePress(ours, ours, ""));
    }

    @Test public void debounceDropsOnlyDuplicateDeliveries() {
        SideButtonGesture gesture = new SideButtonGesture();
        assertTrue(gesture.accept(0L));
        assertFalse(gesture.accept(120L));
        assertFalse(gesture.accept(299L));
        // Two real double presses are always > 300 ms apart (PWM counts down-to-down).
        assertTrue(gesture.accept(300L));
        assertFalse(gesture.accept(599L));
        assertTrue(gesture.accept(5_000L));
    }

    /** The overlay, the manifest alias and the Java constant must name the same component. */
    @Test public void overlayManifestAndConstantAgree() throws Exception {
        String gradle = new String(Files.readAllBytes(new File(APP, "build.gradle.kts").toPath()),
                StandardCharsets.UTF_8);
        String applicationId = group(gradle, "applicationId\\s*=\\s*\"([^\"]+)\"");
        String debugSuffix = group(gradle, "applicationIdSuffix\\s*=\\s*\"([^\"]+)\"");
        String namespace = group(gradle, "namespace\\s*=\\s*\"([^\"]+)\"");

        assertEquals(SideButtonGesture.overlayTargetFor(applicationId + debugSuffix),
                overlayString("debug", "config_doublePressOnPowerTargetActivity"));
        assertEquals(SideButtonGesture.overlayTargetFor(applicationId),
                overlayString("release", "config_doublePressOnPowerTargetActivity"));

        Element alias = alias(xml(new File(APP, "src/main/AndroidManifest.xml")));
        String aliasName = alias.getAttributeNS(ANDROID_NS, "name");
        assertEquals(SideButtonGesture.TOGGLE_ALIAS,
                aliasName.startsWith(".") ? namespace + aliasName : aliasName);
        assertEquals(".MainActivity", alias.getAttributeNS(ANDROID_NS, "targetActivity"));
        assertEquals("false", alias.getAttributeNS(ANDROID_NS, "exported"));

        Document config = xml(new File(OVERLAY, "main/res/values/config.xml"));
        assertEquals(SideButtonGesture.LAUNCH_TARGET_ACTIVITY,
                value(config, "integer", "config_doublePressOnPowerBehavior"));
        assertEquals("0", value(config, "integer", "config_doubleTapPowerGestureMode"));
        assertEquals("false", value(config, "bool", "config_emergencyGestureEnabled"));
    }

    private static String overlayString(String buildType, String name) throws Exception {
        return value(xml(new File(OVERLAY, buildType + "/res/values/target.xml")), "string", name);
    }

    private static Element alias(Document manifest) {
        NodeList aliases = manifest.getElementsByTagName("activity-alias");
        for (int i = 0; i < aliases.getLength(); i++) {
            Element alias = (Element) aliases.item(i);
            if (alias.getAttributeNS(ANDROID_NS, "name").endsWith("SideButtonToggle")) return alias;
        }
        throw new AssertionError("no SideButtonToggle activity-alias in the app manifest");
    }

    private static String value(Document document, String tag, String name) {
        NodeList nodes = document.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (name.equals(element.getAttribute("name"))) return element.getTextContent().trim();
        }
        throw new AssertionError("missing <" + tag + " name=\"" + name + "\">");
    }

    private static Document xml(File file) throws Exception {
        assertTrue(file.getPath(), file.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static String group(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(regex, matcher.find());
        String value = matcher.group(1);
        assertNotNull(value);
        return value;
    }
}
