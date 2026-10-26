package com.verso.document.systemmodel;

import com.verso.document.api.enums.SystemEntityType;
import com.verso.document.api.enums.SystemRelationType;
import com.verso.document.systemmodel.ExtractedModel.Entity;
import com.verso.document.systemmodel.ExtractedModel.Mention;
import com.verso.document.systemmodel.ExtractedModel.Ref;
import com.verso.document.systemmodel.ExtractedModel.Relation;
import com.verso.platform.core.text.TurkishText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic pass of the light system model (ADR-0048 phase 4, ADR-0054): regular expressions and heuristics
 * over the chunks of one document, in chunk order, with Turkish folding for every key. No model call, no I/O: the same
 * chunks always give the same model, so a re-extraction is a replace and nothing drifts.
 *
 * <ul>
 *   <li>CODE: the AnchorCheck code shape (ADR-0047); defined when the line goes on with a separator and a description
 *       ("WMS-204: Stok yetersiz", a table row) or says "... kodu ... anlamına gelir"; CODE_OF its prefix's system.</li>
 *   <li>TERM: "FEFO (First Expired First Out)", "First Expired First Out (FEFO)", "FEFO: First Expired First Out", only
 *       when the initials fit the abbreviation; DEFINES the expansion.</li>
 *   <li>SYSTEM: well-known acronyms (WMS, ERP, ...) and "X sistemi / modülü / uygulaması / portalı / programı".</li>
 *   <li>SCREEN: menu paths ("Stok &gt; Sayım &gt; Sayım Girişi") and "X ekranı / menüsü"; PART_OF the system the path
 *       starts with.</li>
 *   <li>PROCESS and STEP: a heading followed by at least two numbered lines 1, 2, ...; STEP_OF and NEXT_STEP.</li>
 *   <li>ROLE: "X sorumludur", "X tarafından", "Sorumlu: X", reduced to a title by {@link RoleNames} (never a person);
 *       OWNED_BY from the step or the process of the section.</li>
 * </ul>
 *
 * Linear in the text; bounded per document by {@link #MAX_ENTITIES}.
 */
public final class SystemModelExtractor {

    /** Raised when the rules change: every stored model of another version is extracted again by the backfill job. */
    public static final int VERSION = 1;
    /** Entities per document; beyond it the model is marked truncated. */
    public static final int MAX_ENTITIES = 5000;
    static final int MAX_LABEL = 160;
    static final int MAX_VALUE = 240;

    /** One chunk of the document: its position and text. */
    public record ChunkText(int chunkIndex, String content) {

        @Override
        public String toString() {
            return "ChunkText[chunkIndex=" + chunkIndex + "]";
        }
    }

    /** Systems known by their acronym alone. */
    static final Set<String> KNOWN_SYSTEMS = Set.of("ERP", "WMS", "CRM", "MES", "TMS", "SCM", "PLM", "HRM", "HRIS",
            "POS", "EDI", "SAP", "LIMS", "QMS", "CMMS", "EAM", "DMS", "ECM", "YMS", "OMS", "BPM", "ITSM", "MRP", "APS");

    private static final Pattern CODE = Pattern.compile(TurkishText.CODE_REGEX);
    private static final Pattern CODE_DEFINITION = Pattern.compile("^\\s*(?:[:=|–—]|-\\s)\\s*([^|]{3,})");
    private static final Pattern CODE_MEANS = Pattern.compile(
            "^\\s*(?:kodu|hata kodu|durum kodu)\\s*,?\\s*(.{3,}?)\\s+(?:anlamına gelir|anlamina gelir|demektir"
                    + "|ifade eder|gösterir|gosterir|belirtir)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STEP = Pattern.compile(
            "^(?:(?:adım|adim|step)\\s*)?(\\d{1,2})\\s*[.):-]\\s+(\\S.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ACRONYM_FIRST = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\p{Lu}[\\p{Lu}\\p{N}&]{1,9})\\s*\\(([^()]{3,120})\\)");
    private static final Pattern EXPANSION_FIRST = Pattern.compile(
            "([\\p{L}][\\p{L}\\p{N}'’ -]{3,120}?)\\s*\\((\\p{Lu}[\\p{Lu}\\p{N}&]{1,9})\\)");
    private static final Pattern ACRONYM_COLON = Pattern.compile(
            "^\\s*(\\p{Lu}[\\p{Lu}\\p{N}&]{1,9})\\s*(?:[:=–—]|-\\s)\\s*([\\p{L}][^.|]{3,120})");
    private static final Pattern ACRONYM_TOKEN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\p{Lu}[\\p{Lu}\\p{N}&]{1,9})(?:['’]\\p{L}+)?(?![\\p{L}\\p{N}])");
    private static final String TITLE_WORD = "[\\p{Lu}\\p{N}][\\p{L}\\p{N}&./-]*";
    private static final String START = "(?<![\\p{L}\\p{N}])";
    // Only the possessive compound ("Depo Yönetim Sistemi", "WMS modülünde"): "Kullanıcı sisteme girer" is no system.
    private static final Pattern NAMED_SYSTEM = Pattern.compile(START + "((?:" + TITLE_WORD + "\\s+){0,2}" + TITLE_WORD
            + ")\\s+((?iu:sistem(?=i)|modül(?=ü)|modul(?=u)|uygulama(?=s)|portal(?=[ıi])|program(?=[ıi])))\\p{L}*");
    private static final Pattern NAMED_SCREEN = Pattern.compile(START + "((?:" + TITLE_WORD + "\\s+){0,3}" + TITLE_WORD
            + ")\\s+((?iu:ekran(?=[ıi])|menü(?=s)|menu(?=s)))\\p{L}*");
    private static final String SEGMENT = "\\p{Lu}[\\p{L}\\p{N}&./-]*(?:\\s+" + TITLE_WORD + "){0,3}";
    private static final Pattern MENU_PATH = Pattern.compile(
            START + "(" + SEGMENT + ")((?:\\s*[>→»]\\s*" + SEGMENT + "){1,6})");
    private static final Pattern PATH_SEPARATOR = Pattern.compile("\\s*[>→»]\\s*");
    private static final Pattern OWNER_RESPONSIBLE = Pattern.compile(
            "([^.;:!?]{2,80}?)\\s+(?:sorumludur|sorumluluğundadır|sorumlulugundadir|is responsible)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OWNER_BY = Pattern.compile(
            "((?:[\\p{L}\\p{N}'’.()]+\\s+){0,5}?[\\p{L}\\p{N}'’.()]+)\\s+taraf[ıi]ndan",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern OWNER_LABEL = Pattern.compile(
            "(?:^|\\b)(?:sorumlu|sorumlusu|süreç sahibi|surec sahibi|süreç sorumlusu|onaylayan|owner)\\s*:\\s*([^.;|]{2,80})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Words that open a sentence and are not part of a name, screen or system ("Önce Stok > Sayım"). */
    private static final Set<String> LEADING_STOPS = Set.of("once", "sonra", "ardindan", "daha", "lutfen", "bu", "su",
            "ilk", "her", "tum", "yeni", "mevcut", "ilgili", "ayni", "ana", "bir", "the", "in", "on", "go", "open",
            "acin", "gidin", "secin", "tiklayin");
    private static final Set<String> EXPANSION_STOPS = Set.of("ve", "ile", "of", "and", "the", "for", "to", "in",
            "on", "a", "an", "de", "da", "icin");

    private SystemModelExtractor() {
    }

    /** The model of the document whose chunks are given in order. */
    public static ExtractedModel extract(List<ChunkText> chunks) {
        Builder model = new Builder();
        Section section = new Section();
        for (ChunkText chunk : chunks) {
            if (chunk.content() == null) continue;
            for (String raw : chunk.content().split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty()) continue;
                readLine(model, section, line, chunk.chunkIndex());
            }
        }
        section.flush(model);
        model.linkCodes();
        model.mentionKnownTokens(chunks);
        return model.build();
    }

    private static void readLine(Builder model, Section section, String line, int chunk) {
        Matcher step = STEP.matcher(line);
        Ref stepRef = null;
        if (step.matches() && step.group(2).codePoints().anyMatch(Character::isLetter)) {
            stepRef = section.step(model, Integer.parseInt(step.group(1)), step.group(2), chunk);
        } else if (heading(line)) {
            section.flush(model);
            section.heading(line, chunk);
        }
        Set<Ref> systems = systems(model, line, chunk);
        codes(model, line, chunk, systems);
        terms(model, line, chunk);
        screens(model, line, chunk, systems);
        for (Ref role : owners(model, line, chunk)) {
            if (stepRef != null) model.relate(SystemRelationType.OWNED_BY, stepRef, role, null, chunk);
            else section.owner(model, role, chunk);
        }
    }

    /** A short line that is not a sentence: a heading or a caption. */
    static boolean heading(String line) {
        if (line.length() < 3 || line.length() > 100) return false;
        if (!Character.isLetter(line.codePointAt(0))) return false;
        // "Sorumlu: Depo Sorumlusu", a table row or a menu path is content of the section, not a new heading.
        String inner = line.endsWith(":") ? line.substring(0, line.length() - 1) : line;
        if (inner.indexOf(':') >= 0 || inner.indexOf('|') >= 0 || inner.indexOf('>') >= 0 || inner.indexOf('→') >= 0) {
            return false;
        }
        if (CODE.matcher(line).lookingAt()) return false;
        char last = line.charAt(line.length() - 1);
        if (last == '.' || last == ',' || last == ';' || last == '?' || last == '!') return false;
        return line.split("\\s+").length <= 12;
    }

    // --- codes

    private static void codes(Builder model, String line, int chunk, Set<Ref> systems) {
        Matcher matcher = CODE.matcher(line);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 40) continue;
            Ref code = new Ref(SystemEntityType.CODE, TurkishText.foldUpper(token));
            String rest = line.substring(matcher.end());
            Optional<String> definition = codeDefinition(line.substring(0, matcher.start()), rest);
            if (!model.entity(code, token, null)) return;
            model.mention(code, chunk, definition.isPresent());
            definition.ifPresent(value -> model.relate(SystemRelationType.DEFINES, code, null, value, chunk));
            model.codePrefix(code, token, chunk);
            // The one system its line names: "WMS ekranında 204 hatası" is not a code, "WMS'de K-12 hatası" is.
            if (systems.size() == 1) model.relate(SystemRelationType.CODE_OF, code, systems.iterator().next(), null, chunk);
        }
    }

    /** A code at the start of its line (a definition list, a table row) followed by a description, or "... kodu X'tir". */
    static Optional<String> codeDefinition(String before, String rest) {
        if (before.isBlank() || before.strip().matches("[|•*\\-–]+")) {
            Matcher listed = CODE_DEFINITION.matcher(rest);
            if (listed.find()) return value(listed.group(1));
        }
        Matcher means = CODE_MEANS.matcher(rest);
        if (means.find()) return value(means.group(1));
        return Optional.empty();
    }

    // --- terms

    private static void terms(Builder model, String line, int chunk) {
        Matcher first = ACRONYM_FIRST.matcher(line);
        while (first.find()) term(model, first.group(1), first.group(2), chunk);
        Matcher after = EXPANSION_FIRST.matcher(line);
        while (after.find()) {
            String acronym = after.group(2);
            String[] words = after.group(1).strip().split("\\s+");
            // The expansion is the shortest tail of the preceding words whose initials fit the abbreviation.
            for (int take = 1; take <= words.length; take++) {
                String candidate = String.join(" ", List.of(words).subList(words.length - take, words.length));
                if (initialsFit(acronym, candidate)) {
                    term(model, acronym, candidate, chunk);
                    break;
                }
            }
        }
        Matcher colon = ACRONYM_COLON.matcher(line);
        if (colon.find()) term(model, colon.group(1), colon.group(2), chunk);
    }

    private static void term(Builder model, String acronym, String expansion, int chunk) {
        String text = expansion.strip();
        if (!initialsFit(acronym, text)) return;
        Optional<String> value = value(text);
        if (value.isEmpty()) return;
        Ref term = new Ref(SystemEntityType.TERM, TurkishText.foldUpper(acronym));
        if (!model.entity(term, acronym, null)) return;
        model.mention(term, chunk, true);
        model.relate(SystemRelationType.DEFINES, term, null, value.get(), chunk);
        String folded = TurkishText.fold(text);
        if (KNOWN_SYSTEMS.contains(term.key()) || folded.endsWith(" system") || folded.endsWith(" sistemi")) {
            Ref system = new Ref(SystemEntityType.SYSTEM, term.key());
            if (model.entity(system, acronym, null)) model.mention(system, chunk, true);
        }
    }

    /**
     * Whether the expansion's word initials spell the abbreviation in order: every significant word gives the next
     * letter, connecting words ("ve", "of") may be skipped, at most one significant word may give none. Digits and
     * "&amp;" of the abbreviation are ignored ("B2B": "Business to Business").
     */
    static boolean initialsFit(String acronym, String expansion) {
        String letters = TurkishText.foldUpper(acronym).replaceAll("[^A-Z]", "");
        if (letters.length() < 2) return false;
        String[] words = TurkishText.foldUpper(expansion).split("[\\s-]+");
        int at = 0;
        int misses = 0;
        int significant = 0;
        for (String word : words) {
            String clean = word.replaceAll("[^A-Z0-9]", "");
            if (clean.isEmpty()) continue;
            boolean stop = EXPANSION_STOPS.contains(clean.toLowerCase(Locale.ROOT));
            if (at < letters.length() && clean.charAt(0) == letters.charAt(at)) {
                at++;
                if (!stop) significant++;
            } else if (!stop) {
                misses++;
                significant++;
            }
        }
        return at == letters.length() && misses <= 1 && significant >= 2 && significant <= letters.length() + 1;
    }

    // --- systems

    private static Set<Ref> systems(Builder model, String line, int chunk) {
        Set<Ref> found = new LinkedHashSet<>();
        Matcher token = ACRONYM_TOKEN.matcher(line);
        while (token.find()) {
            String word = token.group(1);
            if (!KNOWN_SYSTEMS.contains(TurkishText.foldUpper(word))) continue;
            Ref system = new Ref(SystemEntityType.SYSTEM, TurkishText.foldUpper(word));
            if (model.entity(system, word, null)) {
                model.mention(system, chunk, false);
                found.add(system);
            }
        }
        Matcher named = NAMED_SYSTEM.matcher(line);
        while (named.find()) {
            String name = trimLeading(named.group(1));
            if (name == null || sentenceStartWord(line, named.start(1), name)) continue;
            name = withoutOpeningWord(line, named.start(1), name);
            boolean acronym = name.matches("[\\p{Lu}\\p{N}&]{2,10}");
            String stem = TurkishText.fold(named.group(2));
            Ref system = acronym ? new Ref(SystemEntityType.SYSTEM, TurkishText.foldUpper(name))
                    : new Ref(SystemEntityType.SYSTEM, TurkishText.fold(name) + " " + stem);
            String label = acronym ? name : name + " " + NOUNS.getOrDefault(stem, named.group(2));
            if (model.entity(system, label, null)) {
                model.mention(system, chunk, false);
                found.add(system);
            }
        }
        return found;
    }

    // --- screens

    private static void screens(Builder model, String line, int chunk, Set<Ref> systems) {
        Matcher path = MENU_PATH.matcher(line);
        while (path.find()) {
            List<String> segments = new ArrayList<>();
            String head = trimLeading(path.group(1));
            if (head == null) continue;
            segments.add(head);
            for (String part : PATH_SEPARATOR.split(path.group(2))) if (!part.isBlank()) segments.add(part.strip());
            if (segments.size() < 2) continue;
            String label = String.join(" > ", segments);
            if (label.length() > MAX_LABEL) continue;
            Ref screen = new Ref(SystemEntityType.SCREEN, TurkishText.fold(label));
            if (!model.entity(screen, label, null)) return;
            model.mention(screen, chunk, true);
            Ref first = model.system(segments.getFirst());
            if (first != null) model.relate(SystemRelationType.PART_OF, screen, first, null, chunk);
            else if (systems.size() == 1) model.relate(SystemRelationType.PART_OF, screen, systems.iterator().next(), null, chunk);
        }
        Matcher named = NAMED_SCREEN.matcher(line);
        while (named.find()) {
            String name = trimLeading(named.group(1));
            if (name == null || sentenceStartWord(line, named.start(1), name)) continue;
            String noun = TurkishText.fold(named.group(2)).startsWith("ekran") ? "ekranı" : "menüsü";
            String label = name + " " + noun;
            Ref screen = new Ref(SystemEntityType.SCREEN, TurkishText.fold(label));
            if (!model.entity(screen, label, null)) return;
            model.mention(screen, chunk, false);
            if (systems.size() == 1) model.relate(SystemRelationType.PART_OF, screen, systems.iterator().next(), null, chunk);
        }
    }

    // --- owners

    private static List<Ref> owners(Builder model, String line, int chunk) {
        Set<String> phrases = new LinkedHashSet<>();
        Matcher responsible = OWNER_RESPONSIBLE.matcher(line);
        while (responsible.find()) phrases.add(responsible.group(1));
        Matcher by = OWNER_BY.matcher(line);
        while (by.find()) phrases.add(by.group(1));
        Matcher labelled = OWNER_LABEL.matcher(line);
        while (labelled.find()) phrases.add(labelled.group(1));
        List<Ref> roles = new ArrayList<>();
        for (String phrase : phrases) {
            Optional<String> title = RoleNames.title(phrase);
            if (title.isEmpty()) continue;
            Ref role = new Ref(SystemEntityType.ROLE, TurkishText.fold(title.get()));
            if (!model.entity(role, title.get(), null)) break;
            model.mention(role, chunk, false);
            roles.add(role);
        }
        return roles;
    }

    // --- helpers

    /** A cleaned definition value: trimmed, without trailing punctuation, bounded; empty when nothing is left. */
    static Optional<String> value(String text) {
        String value = text.strip().replaceAll("\\s+", " ");
        while (!value.isEmpty() && ".,;:-–—|".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1).strip();
        }
        if (value.length() > MAX_VALUE) value = value.substring(0, MAX_VALUE).strip();
        return value.codePoints().filter(Character::isLetter).count() < 2 ? Optional.empty() : Optional.of(value);
    }

    /**
     * A single capitalised word at the start of a sentence says nothing ("Kullanıcı ekranında ..." is capitalised only
     * because it opens the sentence); an acronym or a name of several words does.
     */
    private static boolean sentenceStartWord(String line, int start, String name) {
        if (name.contains(" ") || name.matches("[\\p{Lu}\\p{N}&]{2,10}")) return false;
        int at = start - 1;
        while (at >= 0 && Character.isWhitespace(line.charAt(at))) at--;
        return at < 0 || ".!?:;\"“(".indexOf(line.charAt(at)) >= 0;
    }

    /**
     * A sentence's capitalised first word glued in front of a name ("Kayıtlar Depo Yönetim Sisteminde"): before an
     * acronym only the acronym stays, and of three words the first goes. Two words stay ("Depo Yönetim Sistemi").
     */
    private static String withoutOpeningWord(String line, int start, String name) {
        String[] words = name.split(" ");
        if (words.length < 2 || !sentenceStart(line, start)) return name;
        String last = words[words.length - 1];
        if (last.matches("[\\p{Lu}\\p{N}&]{2,10}")) return last;
        return words.length == 3 ? words[1] + " " + words[2] : name;
    }

    private static boolean sentenceStart(String line, int start) {
        int at = start - 1;
        while (at >= 0 && Character.isWhitespace(line.charAt(at))) at--;
        return at < 0 || ".!?:;\"“(".indexOf(line.charAt(at)) >= 0;
    }

    /** The nominative of a system noun, for the label ("Depo Yönetim Sisteminde" is shown as "... Sistemi"). */
    private static final Map<String, String> NOUNS = Map.of("sistem", "Sistemi", "modul", "Modülü",
            "uygulama", "Uygulaması", "portal", "Portalı", "program", "Programı");

    /** The name without opening words of the sentence ("Önce Stok" is "Stok"); null when nothing is left. */
    private static String trimLeading(String name) {
        String[] words = name.strip().split("\\s+");
        int from = 0;
        while (from < words.length && LEADING_STOPS.contains(TurkishText.fold(words[from]))) from++;
        if (from == words.length) return null;
        String rest = String.join(" ", List.of(words).subList(from, words.length));
        return rest.length() > MAX_LABEL ? null : rest;
    }

    private static String label(String text) {
        String label = text.strip().replaceAll("\\s+", " ");
        return label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL).strip() : label;
    }

    /** The heading and numbered steps of the current section, carried across chunk boundaries. */
    private static final class Section {
        private String heading;
        private int headingChunk;
        private Ref process;
        private Ref lastStep;
        private int lastNumber;
        private String firstStep;
        private int firstStepChunk;
        private final List<Ref> owners = new ArrayList<>();
        private int ownersChunk;

        void heading(String line, int chunk) {
            String text = line.endsWith(":") ? line.substring(0, line.length() - 1).strip() : line;
            heading = text.isEmpty() ? null : label(text);
            headingChunk = chunk;
        }

        Ref step(Builder model, int number, String text, int chunk) {
            if (number == 1) {
                endProcess();
                if (heading == null) return null;
                firstStep = text;
                firstStepChunk = chunk;
                lastNumber = 1;
                return null;
            }
            if (number != lastNumber + 1 || (process == null && firstStep == null)) {
                endProcess();
                return null;
            }
            if (process == null) {
                Ref candidate = new Ref(SystemEntityType.PROCESS, TurkishText.fold(heading));
                if (!model.entity(candidate, heading, null)) return null;
                process = candidate;
                model.mention(process, headingChunk, true);
                lastStep = addStep(model, 1, firstStep, firstStepChunk);
                for (Ref owner : owners) model.relate(SystemRelationType.OWNED_BY, process, owner, null, ownersChunk);
                firstStep = null;
            }
            Ref current = addStep(model, number, text, chunk);
            if (current != null && lastStep != null) {
                model.relate(SystemRelationType.NEXT_STEP, lastStep, current, null, chunk);
            }
            lastStep = current;
            lastNumber = number;
            return current;
        }

        private Ref addStep(Builder model, int number, String text, int chunk) {
            Ref step = new Ref(SystemEntityType.STEP, process.key() + "#" + number);
            if (!model.entity(step, label(text), number)) return null;
            model.mention(step, chunk, true);
            model.relate(SystemRelationType.STEP_OF, step, process, null, chunk);
            model.mention(process, chunk, true);
            return step;
        }

        void owner(Builder model, Ref role, int chunk) {
            if (process != null) {
                model.relate(SystemRelationType.OWNED_BY, process, role, null, chunk);
            } else if (heading != null) {
                owners.add(role);
                ownersChunk = chunk;
            }
        }

        void flush(Builder model) {
            endProcess();
            heading = null;
            owners.clear();
        }

        private void endProcess() {
            process = null;
            lastStep = null;
            lastNumber = 0;
            firstStep = null;
        }
    }

    /** Collects entities, mentions and relations without duplicates, in first-seen order. */
    private static final class Builder {
        private final Map<Ref, Entity> entities = new LinkedHashMap<>();
        private final Map<String, Mention> mentions = new LinkedHashMap<>();
        private final Map<String, Relation> relations = new LinkedHashMap<>();
        private final Map<Ref, String> codePrefixes = new LinkedHashMap<>();
        private final Map<Ref, Integer> codeChunks = new LinkedHashMap<>();
        private boolean truncated;

        /** Registers the entity once (the first label wins); false when the per-document bound is reached. */
        boolean entity(Ref ref, String label, Integer ordinal) {
            if (entities.containsKey(ref)) return true;
            if (ref.key().isBlank() || ref.key().length() > MAX_LABEL + 8) return false;
            if (entities.size() >= MAX_ENTITIES) {
                truncated = true;
                return false;
            }
            entities.put(ref, new Entity(ref, label(label), ordinal));
            return true;
        }

        void mention(Ref ref, int chunk, boolean defining) {
            if (!entities.containsKey(ref)) return;
            mentions.merge(ref.type() + "|" + ref.key() + "|" + chunk, new Mention(ref, chunk, defining),
                    (a, b) -> a.defining() ? a : b);
        }

        /** Once per type, source and target (or value); the first evidence chunk wins. */
        void relate(SystemRelationType type, Ref source, Ref target, String value, int chunk) {
            if (!entities.containsKey(source) || (target != null && !entities.containsKey(target))) return;
            if (source.equals(target)) return;
            String key = type + "|" + source + "|" + target + "|" + (value == null ? "" : TurkishText.fold(value));
            relations.putIfAbsent(key, new Relation(type, source, target, value, chunk));
        }

        void codePrefix(Ref code, String token, int chunk) {
            int dash = token.indexOf('-');
            if (dash < 2) return;
            String prefix = token.substring(0, dash);
            if (!prefix.matches("\\p{Lu}{2,6}")) return;
            codePrefixes.putIfAbsent(code, TurkishText.foldUpper(prefix));
            codeChunks.putIfAbsent(code, chunk);
        }

        /** The system a name refers to, when the document already has it (by acronym or by name). */
        Ref system(String name) {
            for (Ref candidate : List.of(new Ref(SystemEntityType.SYSTEM, TurkishText.foldUpper(name)),
                    new Ref(SystemEntityType.SYSTEM, TurkishText.fold(name)))) {
                if (entities.containsKey(candidate)) return candidate;
            }
            return null;
        }

        /** CODE_OF by prefix: "WMS-204" belongs to WMS when WMS is a known system or one of this document. */
        void linkCodes() {
            codePrefixes.forEach((code, prefix) -> {
                Ref system = new Ref(SystemEntityType.SYSTEM, prefix);
                int chunk = codeChunks.get(code);
                if (!entities.containsKey(system) && KNOWN_SYSTEMS.contains(prefix) && entity(system, prefix, null)) {
                    mention(system, chunk, false);
                }
                relate(SystemRelationType.CODE_OF, code, system, null, chunk);
            });
        }

        /** MENTIONED_IN for later plain uses of a defined abbreviation or a system acronym ("FEFO'ya göre ..."). */
        void mentionKnownTokens(List<ChunkText> chunks) {
            Map<String, List<Ref>> byKey = new LinkedHashMap<>();
            for (Ref ref : entities.keySet()) {
                if (ref.type() == SystemEntityType.TERM || ref.type() == SystemEntityType.SYSTEM) {
                    byKey.computeIfAbsent(ref.key(), k -> new ArrayList<>()).add(ref);
                }
            }
            if (byKey.isEmpty()) return;
            for (ChunkText chunk : chunks) {
                if (chunk.content() == null) continue;
                Matcher token = ACRONYM_TOKEN.matcher(chunk.content());
                while (token.find()) {
                    List<Ref> refs = byKey.get(TurkishText.foldUpper(token.group(1)));
                    if (refs != null) for (Ref ref : refs) mention(ref, chunk.chunkIndex(), false);
                }
            }
        }

        ExtractedModel build() {
            return new ExtractedModel(new ArrayList<>(entities.values()), new ArrayList<>(relations.values()),
                    new ArrayList<>(mentions.values()), truncated);
        }
    }
}
