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
package org.bigram.docvalidator;

import org.bigram.docvalidator.config.Configuration;
import org.bigram.docvalidator.config.ValidatorConfiguration;
import org.bigram.docvalidator.distributor.DefaultResultDistributor;
import org.bigram.docvalidator.distributor.ResultDistributor;
import org.bigram.docvalidator.validator.Validator;
import org.bigram.docvalidator.validator.section.SectionValidator;
import org.bigram.docvalidator.validator.section.SectionValidatorFactory;
import org.bigram.docvalidator.validator.sentence.SentenceValidator;
import org.bigram.docvalidator.validator.sentence.SentenceValidatorFactory;
import org.bigram.docvalidator.distributor.ResultDistributorFactory;
import org.bigram.docvalidator.formatter.Formatter;
import org.bigram.docvalidator.model.Document;
import org.bigram.docvalidator.model.DocumentCollection;
import org.bigram.docvalidator.model.ListBlock;
import org.bigram.docvalidator.model.ListElement;
import org.bigram.docvalidator.model.Paragraph;
import org.bigram.docvalidator.model.Section;
import org.bigram.docvalidator.model.Sentence;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Validate all input files using appended Validators.
 */
public class DocumentValidator implements Validator {

  private DocumentValidator(Builder builder) throws DocumentValidatorException {
    Configuration configuration = builder.configuration;
    this.distributor = builder.distributor;

    validators = new ArrayList<Validator>();
    sectionValidators = new ArrayList<SectionValidator>();
    sentenceValidators = new ArrayList<SentenceValidator>();

    loadValidators(configuration);
  }

  /**
   * Load validators written in the configuration file.
   */
  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private void loadValidators(Configuration configuration)
      throws DocumentValidatorException {
    if (configuration == null) {
      throw new IllegalStateException("Configuration object is null");
    }

    //TODO duplicate code...
    for (ValidatorConfiguration config : configuration
        .getSectionValidatorConfigs()) {
      sectionValidators.add(SectionValidatorFactory
          .getInstance(config, configuration.getCharacterTable()));
    }

    for (ValidatorConfiguration config : configuration
        .getSentenceValidatorConfigs()) {
      sentenceValidators.add(SentenceValidatorFactory
          .getInstance(config, configuration.getCharacterTable()));
    }

    //TODO execute document validator
    //TODO execute paragraph validator
  }

  /**
   * Validate the input document collection.
   *
   * @param documentCollection input document collection generated by Parser
   * @return list of validation errors
   */
  public List<ValidationError> check(DocumentCollection documentCollection) {
    distributor.flushHeader();
    List<ValidationError> errors = new ArrayList<ValidationError>();
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
    for (Document document : documentCollection) {
      for (Section section : document) {
        List<ValidationError> newErrors =
            applySentenceValidationsToSection(document, section);
        errors.addAll(newErrors);
      }
    }
    return errors;
  }

  private List<ValidationError> applySentenceValidationsToSection(
      Document document, Section section) {
    List<ValidationError> newErrors = new ArrayList<ValidationError>();
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
    List<ValidationError> errors = new ArrayList<ValidationError>();
    for (Validator validator : validators) {
      errors.addAll(validator.validate(document));
    }
    return errors;
  }

  private List<ValidationError> validateSection(Section section) {
    List<ValidationError> errors = new ArrayList<ValidationError>();
    for (SectionValidator sectionValidator : sectionValidators) {
      errors.addAll(sectionValidator.validate(section));
    }
    return errors;
  }

  private List<ValidationError> validateParagraph(Paragraph paragraph) {
    List<ValidationError> errors = new ArrayList<ValidationError>();
    errors.addAll(validateSentences(paragraph.getSentences()));
    return errors;
  }

  private List<ValidationError> validateSentences(List<Sentence> sentences) {
    List<ValidationError> errors = new ArrayList<ValidationError>();
    for (SentenceValidator sentenceValidator : sentenceValidators) {
      for (Sentence sentence : sentences) {
        errors.addAll(sentenceValidator.validate(sentence));
      }
    }
    return errors;
  }

  /**
   * Constructor only for testing.
   */
  protected DocumentValidator() {
    this.distributor = ResultDistributorFactory
        .createDistributor(Formatter.Type.PLAIN,
            System.out);
    this.validators = new ArrayList<Validator>();
    sectionValidators = new ArrayList<SectionValidator>();
    sentenceValidators = new ArrayList<SentenceValidator>();
  }

  /**
   * Append a specified validator.
   *
   * @param validator Validator used in testing
   */
  protected void appendValidator(Validator validator) {
    this.validators.add(validator);
  }

  @Override
  public List<ValidationError> validate(Document document) {
    return null;
  }

  public void appendSectionValidator(SectionValidator validator) {
    sectionValidators.add(validator);
  }

  /**
   * Builder for DocumentValidator.
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

    public DocumentValidator build() throws DocumentValidatorException {
      return new DocumentValidator(this);
    }
  }

  private final List<Validator> validators;

  private final List<SectionValidator> sectionValidators;

  private final List<SentenceValidator> sentenceValidators;

  private ResultDistributor distributor;

}
