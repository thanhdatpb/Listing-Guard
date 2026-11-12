package com.thanhdatpb.listingguard.api.trademark.tsdr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the status of one mark out of a TSDR answer (ADR-009, "TSDR status check"): an XML document or a JSON document, told
 * apart by their first character. It looks for the names the settings give ({@link TsdrProperties.Fields}, unverified),
 * anywhere in the document, and takes the first plain value of the first name that has one. It never guesses: an answer with
 * no status code is {@link Unparseable}, and its text is never copied into a message.
 *
 * <p>The XML is read as a stream with no DTD and no external entity. Only the three values are kept.
 */
public final class TsdrStatusParser {

    /** What an answer says. {@code description} and {@code date} may be null. */
    public record Parsed(String code, String description, LocalDate date) {
    }

    /** The answer cannot be read as a status. The message names the problem, never the answer's text. */
    public static final class Unparseable extends Exception {
        Unparseable(String message) {
            super(message, null, false, false);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_VALUE_CHARS = 2000;

    private final TsdrProperties.Fields names;

    public TsdrStatusParser(TsdrProperties.Fields names) {
        this.names = names;
    }

    public Parsed parse(byte[] body) throws Unparseable {
        int start = 0;
        if (body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB && (body[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        while (start < body.length && Character.isWhitespace(body[start])) {
            start++;
        }
        if (start >= body.length) {
            throw new Unparseable("The answer is empty.");
        }
        Map<String, String> found = switch (body[start]) {
            case '<' -> xml(body);
            case '{', '[' -> json(body);
            default -> throw new Unparseable("The answer is neither XML nor JSON.");
        };
        String code = first(found, names.statusCodeNames());
        if (code == null) {
            throw new Unparseable("The answer holds no status code under the configured names "
                    + "(listing-guard.trademark.tsdr.fields.status-code-names).");
        }
        if (!code.matches("[A-Za-z0-9]{1,16}")) {
            throw new Unparseable("The status code in the answer is not a short code of letters and digits.");
        }
        String description = first(found, names.statusDescriptionNames());
        return new Parsed(code, description, date(first(found, names.statusDateNames())));
    }

    private static String first(Map<String, String> found, List<String> names) {
        for (String name : names) {
            String value = found.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** The first plain, non-blank value for each wanted name (lower case); the element's own text only. */
    private Map<String, String> xml(byte[] body) throws Unparseable {
        Set<String> wanted = wanted();
        Map<String, String> found = new HashMap<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // an implementation without the property has no DTD loading to switch off
        }
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(body));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName().toLowerCase(Locale.ROOT);
                    if (wanted.contains(name) && !found.containsKey(name)) {
                        String text = plainText(reader);
                        if (text != null) {
                            found.put(name, text);
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new Unparseable("The answer is not well-formed XML.");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing left to release
                }
            }
        }
        return found;
    }

    /** The text of the element the reader is on, or null when it has a child element or no text. Leaves it at its end. */
    private static String plainText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        boolean nested = false;
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                nested = true;
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if (depth == 1 && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                    && text.length() <= MAX_VALUE_CHARS) {
                text.append(reader.getText());
            }
        }
        String value = text.toString().strip();
        return nested || value.isEmpty() ? null : value;
    }

    private Map<String, String> json(byte[] body) throws Unparseable {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new Unparseable("The answer is not well-formed JSON.");
        }
        Map<String, String> found = new HashMap<>();
        walk(root, wanted(), found);
        return found;
    }

    private static void walk(JsonNode node, Set<String> wanted, Map<String, String> found) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String name = entry.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = entry.getValue();
                if (wanted.contains(name) && !found.containsKey(name) && value.isValueNode() && !value.isNull()) {
                    String text = value.asText().strip();
                    if (!text.isEmpty()) {
                        found.put(name, text.length() > MAX_VALUE_CHARS ? text.substring(0, MAX_VALUE_CHARS) : text);
                    }
                }
                walk(value, wanted, found);
            });
        } else if (node.isArray()) {
            node.forEach(item -> walk(item, wanted, found));
        }
    }

    private Set<String> wanted() {
        Set<String> wanted = new HashSet<>(names.statusCodeNames());
        wanted.addAll(names.statusDescriptionNames());
        wanted.addAll(names.statusDateNames());
        return wanted;
    }

    /** {@code 2019-08-20}, {@code 2019-08-20-04:00}, {@code 2019-08-20T10:00:00Z} or {@code 20190820}; anything else is no date. */
    static LocalDate date(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        try {
            if (value.length() >= 10 && value.substring(0, 10).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return LocalDate.parse(value.substring(0, 10));
            }
            if (value.matches("[0-9]{8}")) {
                return LocalDate.of(Integer.parseInt(value.substring(0, 4)), Integer.parseInt(value.substring(4, 6)),
                        Integer.parseInt(value.substring(6, 8)));
            }
        } catch (java.time.DateTimeException e) {
            return null;
        }
        return null;
    }
}
