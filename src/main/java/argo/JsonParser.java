/*
 *  Copyright 2024 Mark Slater
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *  	http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package argo;

import argo.jdom.JsonField;
import argo.jdom.JsonNode;
import argo.jdom.JsonStringNode;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

import static argo.JsonParser.NodeInterningStrategy.INTERN_LEAF_NODES;
import static argo.JsonParser.PositionTracking.TRACK;
import static argo.JsonStreamElement.NonTextJsonStreamElement.END_DOCUMENT;
import static argo.jdom.JsonNodeFactories.*;

/**
 * Provides operations to parse JSON.
 * <p>
 * Instances of this class are immutable, reusable, and thread-safe.
 */
public final class JsonParser {

    private final NodeInterningStrategy nodeInterningStrategy;
    private final PositionTracking positionTracking;

    public JsonParser() {
        this(INTERN_LEAF_NODES, TRACK);
    }

    private JsonParser(final NodeInterningStrategy nodeInterningStrategy, final PositionTracking positionTracking) {
        this.nodeInterningStrategy = nodeInterningStrategy;
        this.positionTracking = positionTracking;
    }

    private static String asString(final Reader reader, final StringBuilder stringBuilder) throws IOException {
        stringBuilder.setLength(0);
        final char[] buffer = new char[1024];
        int charsRead;
        while ((charsRead = reader.read(buffer)) != -1) {
            stringBuilder.append(buffer, 0, charsRead);
        }
        return stringBuilder.toString();
    }

    /**
     * Returns a JsonParser with the given node interning strategy.  Defaults to {@link NodeInterningStrategy#INTERN_LEAF_NODES}.
     *
     * @param nodeInterningStrategy the node interning strategy to use when parsing without streaming.
     * @return a JsonParser with the given node interning strategy.
     */
    public JsonParser nodeInterning(final NodeInterningStrategy nodeInterningStrategy) {
        return new JsonParser(nodeInterningStrategy, positionTracking);
    }

    /**
     * Returns a JsonParser with the given position tracking setting.  Defaults to {@link PositionTracking#TRACK}.
     *
     * @param positionTracking the position tracking setting to use.
     * @return a JsonParser with the given position tracking setting.
     */
    public JsonParser positionTracking(final PositionTracking positionTracking) {
        return new JsonParser(nodeInterningStrategy, positionTracking);
    }

    /**
     * Parses the character stream from the given {@code Reader} into a {@code JsonNode} object.
     *
     * @param reader the {@code Reader} to parse.
     * @return a {@code JsonNode} representing the JSON read from the given {@code Reader}.
     * @throws InvalidSyntaxException if the characters streamed from the given {@code Reader} do not represent valid JSON.
     * @throws IOException            rethrown when reading characters from the given {@code Reader} throws {@code IOException}.
     */
    public JsonNode parse(final Reader reader) throws InvalidSyntaxException, IOException {
        return parse(new ParseExecutor() {
            public void parseUsing(final JsonListener jsonListener) throws InvalidSyntaxException, IOException {
                parseStreaming(reader, jsonListener);
            }
        });
    }

    /**
     * Parses the given JSON {@code String} into a {@code JsonNode} object.
     *
     * @param json the {@code String} to parse.
     * @return a {@code JsonNode} representing the JSON read from the given {@code String}.
     * @throws InvalidSyntaxException if the characters streamed from the given {@code String} do not represent valid JSON.
     */
    public JsonNode parse(final String json) throws InvalidSyntaxException {
        try {
            return parse(new StringReader(json));
        } catch (final IOException e) {
            throw new RuntimeException("Coding failure in Argo:  StringReader threw an IOException");
        }
    }

    /**
     * Parses the character stream from the given {@code Reader} into an {@code Iterator} of {@code JsonStreamElement}s.
     * <p>
     * The {@code next()} and {@code hasNext()} methods of the returned {@code Iterator} throw
     * <ul>
     *     <li>{@link InvalidSyntaxRuntimeException} if the next element could not be read, for example if the next element turns out not to be valid JSON</li>
     *     <li>{@link JsonStreamException} if the underlying character stream failed.</li>
     * </ul>
     *
     * @param reader the {@code Reader} to parse.
     * @return an {@code Iterator} of {@code JsonStreamElement}s reading from the given {@code Reader}.
     */
    public Iterator<JsonStreamElement> parseStreaming(final Reader reader) {
        return new Iterator<JsonStreamElement>() {
            private final PositionedPushbackReader pushbackReader = positionTracking.newPositionedPushbackReader(reader);
            private final Stack<JsonStreamElementType> stack = new Stack<JsonStreamElementType>();
            private JsonStreamElement current;
            private JsonStreamElement next;

            public boolean hasNext() {
                if (current != null && current == END_DOCUMENT) {
                    return false;
                } else if (next == null) {
                    next = getNextElement();
                }
                return true;
            }

            public JsonStreamElement next() {
                if (next == null) {
                    current = getNextElement();
                } else {
                    current = next;
                    next = null;
                }
                return current;
            }

            private JsonStreamElement getNextElement() {
                if (current == null) {
                    stack.push(JsonStreamElementType.START_DOCUMENT);
                    return JsonStreamElement.NonTextJsonStreamElement.START_DOCUMENT;
                } else {
                    try {
                        current.close();
                        return current.jsonStreamElementType().parseNext(pushbackReader, stack);
                    } catch (final IOException e) {
                        throw new JsonStreamException("Failed to read from Reader", e);
                    }
                }
            }

            /**
             * Not supported.
             */
            public void remove() {
                throw new UnsupportedOperationException("JsonParser cannot remove elements from JSON it has parsed");
            }
        };
    }

    /**
     * Parses the given JSON {@code String} into an {@code Iterator} of {@code JsonStreamElement}s.
     * <p>
     * The {@code next()} and {@code hasNext()} methods of the returned {@code Iterator} throw
     * <ul>
     *     <li>{@link InvalidSyntaxRuntimeException} if the next element could not be read, for example if the next element turns out not to be valid JSON</li>
     *     <li>{@link JsonStreamException} if the underlying character stream failed.</li>
     * </ul>
     *
     * @param json the {@code String} to parse.
     * @return an {@code Iterator} of {@code JsonStreamElement}s reading from the given {@code Reader}.
     */
    public Iterator<JsonStreamElement> parseStreaming(final String json) {
        return parseStreaming(new StringReader(json));
    }

    /**
     * Parses the character stream from the given {@code Reader} into calls to the given JsonListener.
     *
     * @param reader       the {@code Reader} to parse.
     * @param jsonListener the JsonListener to notify of parsing events
     * @throws InvalidSyntaxException if the characters streamed from the given {@code Reader} do not represent valid JSON.
     * @throws IOException            rethrown when reading characters from the given {@code Reader} throws {@code IOException}.
     */
    public void parseStreaming(final Reader reader, final JsonListener jsonListener) throws InvalidSyntaxException, IOException {
        parseStreaming(parseStreaming(reader), jsonListener);
    }

    /**
     * Parses the given JSON {@code String} into calls to the given JsonListener.
     *
     * @param json         the {@code String} to parse.
     * @param jsonListener the JsonListener to notify of parsing events
     * @throws InvalidSyntaxException if the characters streamed from the given {@code String} do not represent valid JSON.
     */
    public void parseStreaming(final String json, final JsonListener jsonListener) throws InvalidSyntaxException {
        try {
            parseStreaming(new StringReader(json), jsonListener);
        } catch (final IOException e) {
            throw new RuntimeException("Coding failure in Argo:  StringReader threw an IOException");
        }
    }

    void parseStreaming(final Iterator<JsonStreamElement> stajParser, final JsonListener jsonListener) throws InvalidSyntaxException, IOException {
        try {
            while (stajParser.hasNext()) {
                stajParser.next().visit(jsonListener);
            }
        } catch (final InvalidSyntaxRuntimeException e) {
            throw InvalidSyntaxException.from(e);
        } catch (final JsonStreamException e) {
            throw e.getCause();
        }
    }

    JsonNode parse(final ParseExecutor parseExecutor) throws InvalidSyntaxException, IOException {
        final JsonStringNodeFactory jsonStringNodeFactory = nodeInterningStrategy.newJsonStringNodeFactory();
        final JsonNumberNodeFactory jsonNumberNodeFactory = nodeInterningStrategy.newJsonNumberNodeFactory();
        final StringBuilder stringBuilder = new StringBuilder(32);
        final RootNodeContainer root = new RootNodeContainer();
        final Stack<NodeContainer> stack = new Stack<NodeContainer>();
        try {
            parseExecutor.parseUsing(new JsonListener() {
                public void startDocument() {
                    stack.push(root);
                }

                public void endDocument() {
                    stack.pop();
                }

                public void startArray() {
                    stack.push(new ArrayNodeContainer());
                }

                public void endArray() {
                    final JsonNode jsonNode = stack.pop().buildNode();
                    stack.peek().add(jsonNode);
                }

                public void startObject() {
                    stack.push(new ObjectNodeContainer());
                }

                public void endObject() {
                    final JsonNode jsonNode = stack.pop().buildNode();
                    stack.peek().add(jsonNode);
                }

                public void startField(final Reader name) {
                    try {
                        stack.push(new FieldNodeContainer(jsonStringNodeFactory.jsonStringNode(asString(name, stringBuilder))));
                    } catch (final IOException e) {
                        throw new IORuntimeException(e);
                    }
                }

                public void endField() {
                    final JsonField jsonField = stack.pop().buildField();
                    stack.peek().add(jsonField);
                }

                public void stringValue(final Reader value) {
                    try {
                        stack.peek().add(jsonStringNodeFactory.jsonStringNode(asString(value, stringBuilder)));
                    } catch (final IOException e) {
                        throw new IORuntimeException(e);
                    }
                }

                public void numberValue(final Reader value) {
                    try {
                        stack.peek().add(jsonNumberNodeFactory.jsonNumberNode(asString(value, stringBuilder)));
                    } catch (final IOException e) {
                        throw new IORuntimeException(e);
                    }
                }

                public void trueValue() {
                    stack.peek().add(trueNode());
                }

                public void falseValue() {
                    stack.peek().add(falseNode());
                }

                public void nullValue() {
                    stack.peek().add(nullNode());
                }
            });
        } catch (final IORuntimeException e) {
            throw e.getCause();
        }
        return root.buildNode();
    }

    /**
     * Strategies a {@code JsonParser} can use for object reuse when parsing a document without streaming.
     */
    public enum NodeInterningStrategy {

        /**
         * Use the same object for strings and numbers in a given document that are equal.
         * <p>
         * When the parser encounters a number node or a string node (including field names) equal to one it has previously encountered in the same
         * document, it will use the same object for them, i.e. for any two strings or numbers {@code a} and {@code b} in a given document, if {@code a.equals(b)}
         * then {@code a == b}.
         * <p>
         * This strategy trades a reduction in memory use for a small increase in computational cost.
         */
        INTERN_LEAF_NODES {
            JsonStringNodeFactory newJsonStringNodeFactory() {
                return new InterningJsonStringNodeFactory();
            }

            JsonNumberNodeFactory newJsonNumberNodeFactory() {
                return new InterningJsonNumberNodeFactory();
            }
        },

        /**
         * Use a new object for every string and number in a document.
         * <p>
         * For any two equivalent strings or numbers {@code a} and {@code b} in a given document, {@code a.equals(b)}, but {@code a != b}.
         * <p>
         * This strategy minimises the computational cost of parsing at the expense of increased memory use.
         */
        INTERN_NOTHING {
            JsonStringNodeFactory newJsonStringNodeFactory() {
                return new InstantiatingJsonStringNodeFactory();
            }

            JsonNumberNodeFactory newJsonNumberNodeFactory() {
                return new InstantiatingJsonNumberNodeFactory();
            }
        };

        abstract JsonStringNodeFactory newJsonStringNodeFactory();

        abstract JsonNumberNodeFactory newJsonNumberNodeFactory();
    }

    /**
     * Settings a {@code JsonParser} can use for tracking parsing position for error reporting.
     */
    public enum PositionTracking {

        /**
         * Keep track of the line and column currently being parsed for more precise error messages.  Incurs some computational cost.
         */
        TRACK {
            PositionedPushbackReader newPositionedPushbackReader(final Reader delegate) {
                return new PositionTrackingPushbackReader(delegate);
            }
        },

        /**
         * Do not keep track of line and column currently being parsed, trading reduced computation cost for reduced usability of error messages.
         */
        DO_NOT_TRACK {
            PositionedPushbackReader newPositionedPushbackReader(final Reader delegate) {
                return new PositionIgnoringPushbackReader(delegate);
            }
        };

        abstract PositionedPushbackReader newPositionedPushbackReader(Reader delegate);
    }

    interface ParseExecutor {
        void parseUsing(JsonListener jsonListener) throws InvalidSyntaxException, IOException;
    }

    private interface NodeContainer {

        void add(JsonNode jsonNode);

        void add(JsonField jsonField);

        JsonNode buildNode();

        JsonField buildField();

    }

    private interface JsonStringNodeFactory {
        JsonStringNode jsonStringNode(String value);
    }

    private interface JsonNumberNodeFactory {
        JsonNode jsonNumberNode(String value);
    }

    private static final class IORuntimeException extends RuntimeException {
        private final IOException typedCause;

        IORuntimeException(final IOException cause) {
            super(cause);
            this.typedCause = cause;
        }

        @Override
        public IOException getCause() {
            return typedCause;
        }
    }

    private static final class RootNodeContainer implements NodeContainer {

        private JsonNode value;

        public void add(final JsonNode jsonNode) {
            if (value == null) {
                value = jsonNode;
            } else {
                throw new RuntimeException("Coding failure in Argo:  Attempt to add more than one root node");
            }
        }

        public void add(final JsonField jsonField) {
            throw new RuntimeException("Coding failure in Argo:  Attempt to add a field as root node");
        }

        public JsonNode buildNode() {
            if (value == null) {
                throw new RuntimeException("Coding failure in Argo:  Attempt to build document with no root node");
            } else {
                return value;
            }
        }

        public JsonField buildField() {
            throw new RuntimeException("Coding failure in Argo:  Attempt to build a field from a root node");
        }

    }

    private static final class ArrayNodeContainer implements NodeContainer {
        private final List<JsonNode> elements = new ArrayList<JsonNode>();

        public void add(final JsonNode jsonNode) {
            elements.add(jsonNode);
        }

        public void add(final JsonField jsonField) {
            throw new RuntimeException("Coding failure in Argo:  Attempt to add a field to an array");
        }

        public JsonNode buildNode() {
            return array(elements);
        }

        public JsonField buildField() {
            throw new RuntimeException("Coding failure in Argo:  Attempt to build a field from an array node");
        }

    }

    private static final class ObjectNodeContainer implements NodeContainer {
        private final List<JsonField> fields = new ArrayList<JsonField>();

        public void add(final JsonNode jsonNode) {
            throw new RuntimeException("Coding failure in Argo:  Attempt to add a node to an object");
        }

        public void add(final JsonField jsonField) {
            fields.add(jsonField);
        }

        public JsonNode buildNode() {
            return object(fields);
        }

        public JsonField buildField() {
            throw new RuntimeException("Coding failure in Argo:  Attempt to build a field from a root node");
        }

    }

    private static final class FieldNodeContainer implements NodeContainer {
        private final JsonStringNode name;
        private JsonNode value;

        FieldNodeContainer(final JsonStringNode name) {
            this.name = name;
        }

        public void add(final JsonNode jsonNode) {
            value = jsonNode;
        }

        public void add(final JsonField jsonField) {
            throw new RuntimeException("Coding failure in Argo:  Attempt to add a field to a field");
        }

        public JsonNode buildNode() {
            throw new RuntimeException("Coding failure in Argo:  Attempt to build a JSON node from a field");
        }

        public JsonField buildField() {
            if (value == null) {
                throw new RuntimeException("Coding failure in Argo:  Attempt to create a field without a value");
            } else {
                return field(name, value);
            }
        }
    }

    private static final class InterningJsonStringNodeFactory implements JsonStringNodeFactory {
        private final Map<String, JsonStringNode> existingJsonStringNodes = new HashMap<String, JsonStringNode>();

        public JsonStringNode jsonStringNode(final String value) {
            final JsonStringNode cachedStringNode = existingJsonStringNodes.get(value);
            if (cachedStringNode == null) {
                final JsonStringNode newJsonStringNode = string(value);
                existingJsonStringNodes.put(value, newJsonStringNode);
                return newJsonStringNode;
            } else {
                return cachedStringNode;
            }
        }
    }

    private static final class InstantiatingJsonStringNodeFactory implements JsonStringNodeFactory {
        public JsonStringNode jsonStringNode(final String value) {
            return string(value);
        }
    }

    private static final class InterningJsonNumberNodeFactory implements JsonNumberNodeFactory {
        private final Map<String, JsonNode> existingJsonNumberNodes = new HashMap<String, JsonNode>();

        public JsonNode jsonNumberNode(final String value) {
            final JsonNode cachedNumberNode = existingJsonNumberNodes.get(value);
            if (cachedNumberNode == null) {
                final JsonNode newJsonNumberNode = prevalidatedNumber(new PrevalidatedNumber(value));
                existingJsonNumberNodes.put(value, newJsonNumberNode);
                return newJsonNumberNode;
            } else {
                return cachedNumberNode;
            }
        }
    }

    private static final class InstantiatingJsonNumberNodeFactory implements JsonNumberNodeFactory {
        public JsonNode jsonNumberNode(final String value) {
            return prevalidatedNumber(new PrevalidatedNumber(value));
        }
    }

    /**
     * Internal class
     */
    public static final class PrevalidatedNumber {
        public final String value;

        PrevalidatedNumber(final String value) {
            this.value = value;
        }

    }

}
