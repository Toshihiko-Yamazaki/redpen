/**
 * redpen: a text inspection tool
 * Copyright (C) 2014 Recruit Technologies Co., Ltd. and contributors
 * (see CONTRIBUTORS.md)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.redpen;

import cc.redpen.config.Configuration;
import cc.redpen.config.ValidatorConfiguration;
import cc.redpen.distributor.DefaultResultDistributor;
import cc.redpen.distributor.ResultDistributor;
import cc.redpen.distributor.ResultDistributorFactory;
import cc.redpen.formatter.Formatter;
import cc.redpen.model.*;
import cc.redpen.validator.PreProcessor;
import cc.redpen.validator.Validator;
import cc.redpen.validator.ValidatorFactory;

import java.io.PrintStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Validate all input files using appended Validators.
 */
public class RedPen extends Validator<Document> {

    private final List<Validator<Document>> validators;
    private final List<Validator<Section>> sectionValidators;
    private final List<Validator<Sentence>> sentenceValidators;
    private ResultDistributor distributor;

    private RedPen(Builder builder) throws RedPenException {
        Configuration configuration = builder.configuration;
        this.distributor = builder.distributor;

        validators = new ArrayList<>();
        sectionValidators = new ArrayList<>();
        sentenceValidators = new ArrayList<>();

        loadValidators(configuration);
    }

    /**
     * Constructor only for testing.
     */
    protected RedPen() {
        this.distributor = ResultDistributorFactory
                .createDistributor(Formatter.Type.PLAIN,
                        System.out);
        this.validators = new ArrayList<>();
        sectionValidators = new ArrayList<>();
        sentenceValidators = new ArrayList<>();
    }

    static Type getParameterizedClass(Object obj) {
        if (obj == null) {
            return null;
        }

        Class clazz = obj.getClass();
        Type genericInterface = clazz.getGenericSuperclass();
        ParameterizedType parameterizedType;
        try {
            parameterizedType =
                    ParameterizedType.class.cast(genericInterface);
        } catch (ClassCastException e) {
            return null;
        }

        if (parameterizedType.getActualTypeArguments().length == 0) {
            return null;
        }
        return parameterizedType.getActualTypeArguments()[0];
    }

    /**
     * Load validators written in the configuration file.
     */
    @SuppressWarnings("unchecked")
    private void loadValidators(Configuration configuration)
            throws RedPenException {
        if (configuration == null) {
            throw new IllegalStateException("Configuration object is null");
        }

        for (ValidatorConfiguration config : configuration
                .getValidatorConfigs()) {

            Validator<?> validator = ValidatorFactory.getInstance(
                    config, configuration.getSymbolTable());
            Type type = getParameterizedClass(validator);

            if (type == Sentence.class) {
                this.sentenceValidators.add((Validator<Sentence>) validator);
            } else if (type == Section.class) {
                this.sectionValidators.add((Validator<Section>) validator);
            } else {
                throw new IllegalStateException("No validator for " + type + " block.");
            }
        }
    }

    /**
     * Validate the input document collection.
     *
     * @param documentCollection input document collection generated by Parser
     * @return list of validation errors
     */
    public List<ValidationError> check(DocumentCollection documentCollection) {
        distributor.flushHeader();
        List<ValidationError> errors = new ArrayList<>();
        runDocumentValidators(documentCollection, errors);
        runSectionValidators(documentCollection, errors);
        runSentenceValidators(documentCollection, errors);
        distributor.flushFooter();
        return errors;
    }

    private List<ValidationError> runDocumentValidators(
            DocumentCollection documentCollection,
            List<ValidationError> errors) {
        for (Document document : documentCollection) {
            errors = validateDocument(document);
            for (ValidationError error : errors) {
                error.setFileName(document.getFileName());
                distributor.flushResult(error);
            }
        }
        return errors;
    }

    private List<ValidationError> runSectionValidators(
            DocumentCollection documentCollection,
            List<ValidationError> errors) {
        for (Document document : documentCollection) {
            for (Section section : document) {
                List<ValidationError> newErrors = validateSection(section);
                for (ValidationError error : newErrors) {
                    error.setFileName(document.getFileName());
                    distributor.flushResult(error);
                }
                errors.addAll(newErrors);
            }
        }
        return errors;
    }

    private List<ValidationError> runSentenceValidators(
            DocumentCollection documentCollection,
            List<ValidationError> errors) {
        runSentencePreProcessorsToDocumentCollection(documentCollection, errors);
        runSentenceValidatorsToDocumentCollection(documentCollection, errors);
        return errors;
    }

    private void runSentencePreProcessorsToDocumentCollection(
            DocumentCollection documentCollection, List<ValidationError> errors) {
        for (Document document : documentCollection) {
            for (Section section : document) {
                applySentencePreProcessorsToSection(document, section);
            }
        }
    }

    private void applySentencePreProcessorsToSection(Document document, Section section) {
        // apply paragraphs
        for (Paragraph paragraph : section.getParagraphs()) {
            preprocessSentences(paragraph.getSentences());
        }
        // apply to section header
        preprocessSentences(section.getHeaderContents());
        // apply to lists
        for (ListBlock listBlock : section.getListBlocks()) {
            for (ListElement listElement : listBlock.getListElements()) {
                preprocessSentences(listElement.getSentences());
            }
        }
    }

    private void preprocessSentences(List<Sentence> sentences) {
        for (Validator<Sentence> sentenceValidator : sentenceValidators) {
            Class<?> clazz = sentenceValidator.getClass();
            if (!clazz.getSuperclass().equals(cc.redpen.validator.PreProcessor.class)) {
                return;
            }
            PreProcessor<Sentence> preprocessor = (PreProcessor<Sentence>) sentenceValidator;
            for (Sentence sentence : sentences) {
                preprocessor.preprocess(sentence);
            }
        }
    }

    private void runSentenceValidatorsToDocumentCollection(
            DocumentCollection documentCollection, List<ValidationError> errors) {
        for (Document document : documentCollection) {
            for (Section section : document) {
                List<ValidationError> newErrors =
                        applySentenceValidationsToSection(document, section);
                errors.addAll(newErrors);
            }
        }
    }

    private List<ValidationError> applySentenceValidationsToSection(
            Document document, Section section) {
        List<ValidationError> newErrors = new ArrayList<>();
        // apply paragraphs
        for (Paragraph paragraph : section.getParagraphs()) {
            newErrors.addAll(validateParagraph(paragraph));
        }

        // apply to section header
        newErrors.addAll(validateSentences(section.getHeaderContents()));

        // apply to lists
        for (ListBlock listBlock : section.getListBlocks()) {
            for (ListElement listElement : listBlock.getListElements()) {
                newErrors.addAll(validateSentences(listElement.getSentences()));
            }
        }
        for (ValidationError error : newErrors) {
            error.setFileName(document.getFileName());
            distributor.flushResult(error);
        }
        return newErrors;
    }

    private List<ValidationError> validateDocument(Document document) {
        List<ValidationError> errors = new ArrayList<>();
        for (Validator<Document> validator : validators) {
            errors.addAll(validator.validate(document));
        }
        return errors;
    }

    private List<ValidationError> validateSection(Section section) {
        List<ValidationError> errors = new ArrayList<>();
        for (Validator<Section> sectionValidator : sectionValidators) {
            errors.addAll(sectionValidator.validate(section));
        }
        return errors;
    }

    private List<ValidationError> validateParagraph(Paragraph paragraph) {
        List<ValidationError> errors = new ArrayList<>();
        errors.addAll(validateSentences(paragraph.getSentences()));
        return errors;
    }

    private List<ValidationError> validateSentences(List<Sentence> sentences) {
        List<ValidationError> errors = new ArrayList<>();
        for (Validator<Sentence> sentenceValidator : sentenceValidators) {
            for (Sentence sentence : sentences) {
                errors.addAll(sentenceValidator.validate(sentence));
            }
        }
        return errors;
    }

    /**
     * Run validation.
     *
     * @param document input
     * @return set of errors
     */
    @Override
    public List<ValidationError> validate(Document document) {
        return null;
    }

    public void appendSectionValidator(Validator<Section> validator) {
        sectionValidators.add(validator);
    }

    /**
     * Builder for {@link cc.redpen.RedPen}.
     */
    public static class Builder {

        private Configuration configuration;

        private ResultDistributor distributor = new DefaultResultDistributor(
                new PrintStream(System.out)
        );

        public Builder setConfiguration(Configuration configuration) {
            this.configuration = configuration;
            return this;
        }

        public Builder setResultDistributor(ResultDistributor distributor) {
            this.distributor = distributor;
            return this;
        }

        public RedPen build() throws RedPenException {
            return new RedPen(this);
        }
    }
}
