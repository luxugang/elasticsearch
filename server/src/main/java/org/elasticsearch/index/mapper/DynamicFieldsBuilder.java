/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.CheckedBiConsumer;
import org.elasticsearch.common.CheckedRunnable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.ObjectMapper.Dynamic;

import java.io.IOException;
import java.time.format.DateTimeParseException;

/**
 * Encapsulates the logic for dynamically creating fields as part of document parsing.
 * Objects are always created the same, but leaf fields can be mapped under properties, as concrete fields that get indexed,
 * or as runtime fields that are evaluated at search-time and have no indexing overhead.
 */
final class DynamicFieldsBuilder {
    private static final Concrete CONCRETE = new Concrete(DocumentParser::parseObjectOrField);
    static final DynamicFieldsBuilder DYNAMIC_TRUE = new DynamicFieldsBuilder(CONCRETE);
    static final DynamicFieldsBuilder DYNAMIC_RUNTIME = new DynamicFieldsBuilder(new Runtime());

    private final Strategy strategy;

    private DynamicFieldsBuilder(Strategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Creates a dynamic field based on the value of the current token being parsed from an incoming document.
     * Makes decisions based on the type of the field being found, looks at matching dynamic templates and
     * delegates to the appropriate strategy which depends on the current dynamic mode.
     * The strategy defines if fields are going to be mapped as ordinary or runtime fields.
     */
    void createDynamicFieldFromValue(final ParseContext context,
                                           XContentParser.Token token,
                                           String name) throws IOException {
        if (token == XContentParser.Token.VALUE_STRING) {
            String text = context.parser().text();

            boolean parseableAsLong = false;
            try {
                Long.parseLong(text);
                parseableAsLong = true;
            } catch (NumberFormatException e) {
                // not a long number
            }

            boolean parseableAsDouble = false;
            try {
                Double.parseDouble(text);
                parseableAsDouble = true;
            } catch (NumberFormatException e) {
                // not a double number
            }

            if (parseableAsLong && context.root().numericDetection()) {
                createDynamicField(context, name, DynamicTemplate.XContentFieldType.LONG,
                    () -> strategy.newDynamicLongField(context, name));
            } else if (parseableAsDouble && context.root().numericDetection()) {
                createDynamicField(context, name, DynamicTemplate.XContentFieldType.DOUBLE,
                    () -> strategy.newDynamicDoubleField(context, name));
            } else if (parseableAsLong == false && parseableAsDouble == false && context.root().dateDetection()) {
                // We refuse to match pure numbers, which are too likely to be
                // false positives with date formats that include eg.
                // `epoch_millis` or `YYYY`
                for (DateFormatter dateTimeFormatter : context.root().dynamicDateTimeFormatters()) {
                    try {
                        dateTimeFormatter.parse(text);
                    } catch (ElasticsearchParseException | DateTimeParseException | IllegalArgumentException e) {
                        // failure to parse this, continue
                        continue;
                    }
                    createDynamicDateField(context, name, dateTimeFormatter,
                        () -> strategy.newDynamicDateField(context, name, dateTimeFormatter));
                    return;
                }
                createDynamicField(context, name, DynamicTemplate.XContentFieldType.STRING,
                    () -> strategy.newDynamicStringField(context, name));
            } else {
                createDynamicField(context, name, DynamicTemplate.XContentFieldType.STRING,
                    () -> strategy.newDynamicStringField(context, name));
            }
        } else if (token == XContentParser.Token.VALUE_NUMBER) {
            XContentParser.NumberType numberType = context.parser().numberType();
            if (numberType == XContentParser.NumberType.INT
                || numberType == XContentParser.NumberType.LONG
                || numberType == XContentParser.NumberType.BIG_INTEGER) {
                createDynamicField(context, name, DynamicTemplate.XContentFieldType.LONG,
                    () -> strategy.newDynamicLongField(context, name));
            } else if (numberType == XContentParser.NumberType.FLOAT
                || numberType == XContentParser.NumberType.DOUBLE
                || numberType == XContentParser.NumberType.BIG_DECIMAL) {
                createDynamicField(context, name, DynamicTemplate.XContentFieldType.DOUBLE,
                    () -> strategy.newDynamicDoubleField(context, name));
            } else {
                throw new IllegalStateException("Unable to parse number of type [" + numberType + "]");
            }
        } else if (token == XContentParser.Token.VALUE_BOOLEAN) {
            createDynamicField(context, name, DynamicTemplate.XContentFieldType.BOOLEAN,
                () -> strategy.newDynamicBooleanField(context, name));
        } else if (token == XContentParser.Token.VALUE_EMBEDDED_OBJECT) {
            //runtime binary fields are not supported, hence binary objects always get created as concrete fields
            createDynamicField(context, name, DynamicTemplate.XContentFieldType.BINARY,
                () -> CONCRETE.newDynamicBinaryField(context, name));
        } else {
            createDynamicStringFieldFromTemplate(context, name);
        }
    }

    /**
     * Returns a dynamically created object mapper, eventually based on a matching dynamic template.
     * Note that objects are always mapped under properties.
     */
    Mapper createDynamicObjectMapper(ParseContext context, String name) {
        //dynamic:runtime maps objects under properties, exactly like dynamic:true
        Mapper mapper = createObjectMapperFromTemplate(context, name);
        return mapper != null ? mapper :
            new ObjectMapper.Builder(name, context.indexSettings().getIndexVersionCreated()).enabled(true).build(context.path());
    }

    /**
     * Returns a dynamically created object mapper, based exclusively on a matching dynamic template, null otherwise.
     * Note that objects are always mapped under properties.
     */
    Mapper createObjectMapperFromTemplate(ParseContext context, String name) {
        Mapper.Builder templateBuilder = findTemplateBuilder(context, name, DynamicTemplate.XContentFieldType.OBJECT, null);
        return templateBuilder == null ? null : templateBuilder.build(context.path());
    }

    /**
     * Creates a dynamic string field based on a matching dynamic template.
     * No field is created in case there is no matching dynamic template.
     */
    void createDynamicStringFieldFromTemplate(ParseContext context, String name) throws IOException {
        createDynamicField(context, name, DynamicTemplate.XContentFieldType.STRING, () -> {});
    }

    private static void createDynamicDateField(ParseContext context,
                                               String name,
                                               DateFormatter dateFormatter,
                                               CheckedRunnable<IOException> createDynamicField) throws IOException {
        createDynamicField(context, name, DynamicTemplate.XContentFieldType.DATE, dateFormatter, createDynamicField);
    }

    private static void createDynamicField(ParseContext context,
                                           String name,
                                           DynamicTemplate.XContentFieldType matchType,
                                           CheckedRunnable<IOException> dynamicFieldStrategy) throws IOException {
        assert matchType != DynamicTemplate.XContentFieldType.DATE;
        createDynamicField(context, name, matchType, null, dynamicFieldStrategy);
    }

    private static void createDynamicField(ParseContext context,
                                           String name,
                                           DynamicTemplate.XContentFieldType matchType,
                                           DateFormatter dateFormatter,
                                           CheckedRunnable<IOException> dynamicFieldStrategy) throws IOException {
        Mapper.Builder templateBuilder = findTemplateBuilder(context, name, matchType, dateFormatter);
        if (templateBuilder == null) {
            dynamicFieldStrategy.run();
        } else {
            CONCRETE.createDynamicField(templateBuilder, context);
        }
    }

    /**
     * Find a template. Returns {@code null} if no template could be found.
     * @param context        the parse context for this document
     * @param name           the current field name
     * @param matchType      the type of the field in the json document or null if unknown
     * @param dateFormatter  a date formatter to use if the type is a date, null if not a date or is using the default format
     * @return a mapper builder, or null if there is no template for such a field
     */
    private static Mapper.Builder findTemplateBuilder(ParseContext context,
                                                      String name,
                                                      DynamicTemplate.XContentFieldType matchType,
                                                      DateFormatter dateFormatter) {
        DynamicTemplate dynamicTemplate = context.root().findTemplate(context.path(), name, matchType);
        if (dynamicTemplate == null) {
            return null;
        }
        String dynamicType = matchType.defaultMappingType();
        Mapper.TypeParser.ParserContext parserContext = context.parserContext(dateFormatter);
        String mappingType = dynamicTemplate.mappingType(dynamicType);
        Mapper.TypeParser typeParser = parserContext.typeParser(mappingType);
        if (typeParser == null) {
            throw new MapperParsingException("failed to find type parsed [" + mappingType + "] for [" + name + "]");
        }
        return typeParser.parse(name, dynamicTemplate.mappingForName(name, dynamicType), parserContext);
    }

    /**
     * Defines how leaf fields of type string, long, double, boolean and date are dynamically mapped
     */
    private interface Strategy {
        void newDynamicStringField(ParseContext context, String name) throws IOException;
        void newDynamicLongField(ParseContext context, String name) throws IOException;
        void newDynamicDoubleField(ParseContext context, String name) throws IOException;
        void newDynamicBooleanField(ParseContext context, String name) throws IOException;
        void newDynamicDateField(ParseContext context, String name, DateFormatter dateFormatter) throws IOException;
    }

    /**
     * Dynamically creates concrete fields, as part of the properties section.
     * Use for leaf fields, when their parent object is mapped as dynamic:true
     * @see Dynamic
     */
    private static final class Concrete implements Strategy {
        private final CheckedBiConsumer<ParseContext, Mapper, IOException> parseField;

        Concrete(CheckedBiConsumer<ParseContext, Mapper, IOException> parseField) {
            this.parseField = parseField;
        }

        void createDynamicField(Mapper.Builder builder, ParseContext context) throws IOException {
            Mapper mapper = builder.build(context.path());
            context.addDynamicMapper(mapper);
            parseField.accept(context, mapper);
        }

        @Override
        public void newDynamicStringField(ParseContext context, String name) throws IOException {
            createDynamicField(new TextFieldMapper.Builder(name, context.indexAnalyzers()).addMultiField(
                    new KeywordFieldMapper.Builder("keyword").ignoreAbove(256)), context);
        }

        @Override
        public void newDynamicLongField(ParseContext context, String name) throws IOException {
            createDynamicField(
                new NumberFieldMapper.Builder(name, NumberFieldMapper.NumberType.LONG, context.indexSettings().getSettings()), context);
        }

        @Override
        public void newDynamicDoubleField(ParseContext context, String name) throws IOException {
            // no templates are defined, we use float by default instead of double
            // since this is much more space-efficient and should be enough most of
            // the time
            createDynamicField(new NumberFieldMapper.Builder(name,
                NumberFieldMapper.NumberType.FLOAT, context.indexSettings().getSettings()), context);
        }

        @Override
        public void newDynamicBooleanField(ParseContext context, String name) throws IOException {
            createDynamicField(new BooleanFieldMapper.Builder(name), context);
        }

        @Override
        public void newDynamicDateField(ParseContext context, String name, DateFormatter dateTimeFormatter) throws IOException {
            Settings settings = context.indexSettings().getSettings();
            boolean ignoreMalformed = FieldMapper.IGNORE_MALFORMED_SETTING.get(settings);
            createDynamicField(new DateFieldMapper.Builder(name, DateFieldMapper.Resolution.MILLISECONDS,
                dateTimeFormatter, ignoreMalformed, context.indexSettings().getIndexVersionCreated()), context);
        }

        void newDynamicBinaryField(ParseContext context, String name) throws IOException {
            createDynamicField(new BinaryFieldMapper.Builder(name), context);
        }
    }

    /**
     * Dynamically creates runtime fields, in the runtime section.
     * Used for leaf fields, when their parent object is mapped as dynamic:runtime.
     * @see Dynamic
     */
    private static final class Runtime implements Strategy {
        @Override
        public void newDynamicStringField(ParseContext context, String name) {
            String fullName = context.path().pathAsText(name);
            RuntimeFieldType runtimeFieldType = context.getDynamicRuntimeFieldsBuilder().newDynamicStringField(fullName);
            context.addDynamicRuntimeField(runtimeFieldType);
        }

        @Override
        public void newDynamicLongField(ParseContext context, String name) {
            String fullName = context.path().pathAsText(name);
            RuntimeFieldType runtimeFieldType = context.getDynamicRuntimeFieldsBuilder().newDynamicLongField(fullName);
            context.addDynamicRuntimeField(runtimeFieldType);
        }

        @Override
        public void newDynamicDoubleField(ParseContext context, String name) {
            String fullName = context.path().pathAsText(name);
            RuntimeFieldType runtimeFieldType = context.getDynamicRuntimeFieldsBuilder().newDynamicDoubleField(fullName);
            context.addDynamicRuntimeField(runtimeFieldType);
        }

        @Override
        public void newDynamicBooleanField(ParseContext context, String name) {
            String fullName = context.path().pathAsText(name);
            RuntimeFieldType runtimeFieldType = context.getDynamicRuntimeFieldsBuilder().newDynamicBooleanField(fullName);
            context.addDynamicRuntimeField(runtimeFieldType);
        }

        @Override
        public void newDynamicDateField(ParseContext context, String name, DateFormatter dateFormatter) {
            String fullName = context.path().pathAsText(name);
            RuntimeFieldType runtimeFieldType = context.getDynamicRuntimeFieldsBuilder().newDynamicDateField(fullName, dateFormatter);
            context.addDynamicRuntimeField(runtimeFieldType);
        }
    }
}