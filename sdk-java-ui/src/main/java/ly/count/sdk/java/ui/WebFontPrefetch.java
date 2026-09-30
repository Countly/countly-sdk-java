package ly.count.sdk.java.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.animation.PauseTransition;
import javafx.scene.web.WebEngine;
import javafx.util.Duration;
import ly.count.sdk.java.internal.SDKCore;

/**
 * Puts the web fonts a widget page uses into WebKit's memory cache before the first widget is shown.
 * <p>
 * A Countly widget or content block styles its text with web fonts fetched from the server, and it
 * paints before they arrive: the text appears in a fallback face and swaps a second later. Every
 * other platform's SDK avoids this without trying, because the engine it embeds has a persistent
 * HTTP cache. JavaFX's WebKit has none (JDK-8014501) and its loader calls
 * {@code setUseCaches(false)}, so {@link java.net.ResponseCache} is never consulted either: the same
 * faces are fetched again on every run.
 * <p>
 * What is left is the engine's in-process memory cache, which is shared by every web view. So the
 * first page a process loads is made to be a hidden one that asks for nothing but those fonts,
 * during {@link FxSurfaces#prewarm()}, which happens at initialization rather than when a widget is
 * due. By the time a widget page needs a face, the fetch has already happened.
 * <p>
 * Which faces those are is only known once a widget page has been seen, so the list is harvested
 * from the first one ({@link #remember(WebEngine)}) and kept in the SDK's storage directory for the
 * next run. The first run in a fresh installation therefore gains nothing; every run after it does.
 * <p>
 * Faces this engine cannot decode are left out. WebKit here fetches a {@code woff2} and then fails
 * to decode it, so a page whose {@code @font-face} lists {@code woff2} before {@code woff} pays for
 * two downloads and uses the second: only the formats that can actually be used are prefetched.
 */
final class WebFontPrefetch {

    /** Where the harvested list lives, inside the SDK's own storage directory. */
    private static final String FILE_NAME = "countly_display_fonts.txt";

    /** A page needs a handful of faces. A cap keeps a hostile or broken page from queueing hundreds. */
    private static final int MAX_URLS = 8;

    private static final int MAX_URL_LENGTH = 512;

    /** The formats this engine can decode, in the order they are preferred. */
    private static final List<String> USABLE_EXTENSIONS = Arrays.asList(".woff", ".ttf", ".otf");

    /**
     * How long after the page loads the faces are read.
     * <p>
     * Not a guess: at load every face still reads {@code unloaded}, so harvesting then cannot tell
     * the family the card uses from the four it merely declares. A moment later the used ones report
     * {@code loading} or {@code loaded}, which is the signal this waits for.
     */
    static Duration settle = Duration.millis(2500); // shortened for testing purposes

    /**
     * Collects the {@code @font-face} sources of the loaded document, one URL per line.
     * <p>
     * Only the families the page actually used. A dashboard page declares every font it offers -
     * Inter, Lato, Oswald, Roboto-Mono, Ubuntu - and uses one of them, so remembering them in
     * stylesheet order spends the budget on faces no card will ever request. When nothing reports as
     * used, every declared face is kept rather than none: a wrong guess about which font is wanted
     * is better than an empty cache.
     */
    private static final String HARVEST_SCRIPT =
        "(function(){var out=[];try{"
            + "var used={};var anyUsed=false;"
            + "if(document.fonts&&document.fonts.forEach){document.fonts.forEach(function(f){"
            + "if(f.status!=='unloaded'){used[(f.family||'').replace(/['\"]/g,'')]=true;anyUsed=true;}});}"
            + "var sheets=document.styleSheets;"
            + "for(var i=0;i<sheets.length;i++){var rules;try{rules=sheets[i].cssRules;}catch(e){continue;}"
            + "if(!rules){continue;}"
            + "for(var j=0;j<rules.length;j++){var r=rules[j];"
            + "if(!r||!r.style||r.type!==5){continue;}"
            + "var family=(r.style.getPropertyValue('font-family')||'').replace(/['\"]/g,'');"
            + "if(anyUsed&&!used[family]){continue;}"
            + "var src=r.style.getPropertyValue('src')||'';"
            + "var m=src.match(/url\\((['\"]?)([^'\")]+)\\1\\)/g)||[];"
            + "for(var k=0;k<m.length;k++){var u=m[k].replace(/^url\\((['\"]?)/,'').replace(/(['\"]?)\\)$/,'');"
            + "try{u=new URL(u,document.baseURI).href;}catch(e2){}"
            + "out.push(u);}}}}catch(e3){}"
            + "return out.join('\\n');})()";

    private WebFontPrefetch() {
    }

    /**
     * Remembers the fonts of a widget page that has just loaded, for the next run. Cheap: reads the
     * page's own stylesheets and writes a few lines, and only when the list has changed. Call on the
     * JavaFX application thread.
     *
     * @param engine the engine whose loaded document to harvest
     */
    static void remember(WebEngine engine) {
        // Read after the page has settled, not on load: see the field's own note.
        PauseTransition wait = new PauseTransition(settle);
        wait.setOnFinished(event -> harvest(engine));
        wait.play();
    }

    private static void harvest(WebEngine engine) {
        List<String> urls;
        try {
            urls = usableUrls(String.valueOf(engine.executeScript(HARVEST_SCRIPT)));
        } catch (Throwable t) {
            // A page that refuses to be scripted costs the next run its head start, nothing more.
            UiLog.d("[WebFontPrefetch] remember, could not read the page's fonts, [" + t + "]");
            return;
        }
        if (urls.isEmpty()) {
            return;
        }

        File file = listFile();
        if (file == null) {
            return;
        }
        // Off the application thread: the widget is on screen and must not wait for a disk write.
        Thread writer = new Thread(() -> write(file, urls), "cly-font-list");
        writer.setDaemon(true);
        writer.start();

        // And warm them now, in this run. The card that taught us these URLs may be closed before it
        // finishes fetching them - closing its window cancels the download - which left the next card
        // in the same run fetching from scratch. The warm-up view outlives every card, so a fetch
        // started there cannot be cancelled by one closing.
        FxSurfaces.warmFonts(warmupPageFor(urls));
    }

    /**
     * The page to load into the hidden warm-up view: it declares the remembered faces and asks for
     * each one, which is what makes the engine fetch it.
     *
     * @return the page, or {@code null} when nothing has been remembered yet
     */
    static String warmupPage() {
        return warmupPageFor(read());
    }

    /**
     * @param urls the faces to fetch
     * @return a page that asks for each of them, or {@code null} when there are none
     */
    static String warmupPageFor(List<String> urls) {
        if (urls.isEmpty()) {
            return null;
        }

        StringBuilder faces = new StringBuilder();
        StringBuilder families = new StringBuilder();
        for (int i = 0; i < urls.size(); i++) {
            String family = "cly-prefetch-" + i;
            faces.append("@font-face{font-family:'").append(family).append("';src:url('")
                .append(urls.get(i)).append("') format('").append(formatOf(urls.get(i))).append("');}");
            if (i > 0) {
                families.append(',');
            }
            families.append('\'').append(family).append('\'');
        }

        // document.fonts.load, not layout: the view has no scene, so nothing would use the faces and
        // a declared but unused face is never fetched.
        return "<html><head><style>" + faces + "</style></head><body><script>"
            + "if(document.fonts&&document.fonts.load){[" + families + "].forEach(function(f){"
            + "try{document.fonts.load('16px '+f);}catch(e){}});}"
            + "</script></body></html>";
    }

    /** @return how many faces are remembered, for logging and tests */
    static int rememberedCount() {
        return read().size();
    }

    /** Forgets the remembered list. Test seam, and the way an integrator's storage stays tidy. */
    static void forget() {
        File file = listFile();
        if (file != null && file.exists() && !file.delete()) {
            UiLog.d("[WebFontPrefetch] forget, could not delete [" + file + "]");
        }
    }

    /**
     * Keeps only what is worth fetching: absolute http(s) URLs in a format this engine can decode,
     * free of the characters that would let a URL break out of the page built around it, deduplicated
     * and capped.
     */
    private static List<String> usableUrls(String harvested) {
        if (harvested == null || harvested.isEmpty() || "null".equals(harvested)) {
            return new ArrayList<>();
        }

        Set<String> unique = new LinkedHashSet<>();
        // One format per face. A page lists the same face as woff2, woff and ttf, and this engine
        // uses the first of those it can decode: warming the others would spend the budget on bytes
        // no page will ask for. USABLE_EXTENSIONS is in the engine's own order of preference, and
        // the harvested lines keep the stylesheet's order, so the first usable line for a face wins.
        Set<String> faces = new LinkedHashSet<>();
        for (String line : harvested.split("\n")) {
            String url = line.trim();
            if (!isUsable(url) || !faces.add(withoutExtension(url))) {
                continue;
            }
            unique.add(url);
            if (unique.size() == MAX_URLS) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private static boolean isUsable(String url) {
        if (url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            return false;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || ":/._?&=%~+-#".indexOf(c) >= 0;
            if (!safe) {
                return false;
            }
        }
        return extensionOf(url) != null;
    }

    /** @return the usable extension the URL's path ends in, ignoring a query or fragment, or null */
    private static String extensionOf(String url) {
        String path = url;
        int cut = path.indexOf('?');
        if (cut < 0) {
            cut = path.indexOf('#');
        }
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        String lower = path.toLowerCase();
        for (String extension : USABLE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return extension;
            }
        }
        return null;
    }

    /** @return the URL without the font extension, which is what identifies one face */
    private static String withoutExtension(String url) {
        String extension = extensionOf(url);
        return extension == null ? url : url.substring(0, url.length() - extension.length());
    }

    private static String formatOf(String url) {
        String extension = extensionOf(url);
        if (".woff".equals(extension)) {
            return "woff";
        }
        if (".otf".equals(extension)) {
            return "opentype";
        }
        return "truetype";
    }

    private static File listFile() {
        File directory = SDKCore.sdkStorageDirectory();
        if (directory == null) {
            // Before initialization, or after the SDK was stopped: no place to keep it.
            return null;
        }
        return new File(directory, FILE_NAME);
    }

    private static synchronized void write(File file, List<String> urls) {
        try {
            String contents = String.join("\n", urls);
            Path path = file.toPath();
            if (file.exists() && contents.equals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))) {
                return;
            }
            Files.write(path, contents.getBytes(StandardCharsets.UTF_8));
            UiLog.d("[WebFontPrefetch] remembered [" + urls.size() + "] font URLs for the next run");
        } catch (Throwable t) {
            // The next run just pays for the fonts again.
            UiLog.d("[WebFontPrefetch] could not remember the page's fonts, [" + t + "]");
        }
    }

    private static synchronized List<String> read() {
        File file = listFile();
        if (file == null || !file.exists()) {
            return new ArrayList<>();
        }
        try {
            return usableUrls(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            UiLog.d("[WebFontPrefetch] could not read the remembered fonts, [" + t + "]");
            return new ArrayList<>();
        }
    }
}
