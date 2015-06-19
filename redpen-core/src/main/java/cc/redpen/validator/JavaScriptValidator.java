/**
 * redpen: a text inspection tool
 * Copyright (c) 2014-2015 Recruit Technologies Co., Ltd. and contributors
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
package cc.redpen.validator;

import cc.redpen.RedPenException;
import cc.redpen.model.Document;
import cc.redpen.model.Section;
import cc.redpen.model.Sentence;
import cc.redpen.parser.LineOffset;
import cc.redpen.tokenizer.TokenElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.*;

/**
 * <p>A Validator implementation load JavaScript dynamically.</p>
 * <p>files which name end with &quot;.js&quot; and located in &quot;js&quot; (can be specified with &quot;script-path&quot; property) directory will be treated as JavaScript validator implementation. Functions with the following signature will be called upon validation time:</p>
 * <pre>
 *     var message = "<i>validation error message</i> {0}";
 *     function preValidateSentence(sentence) {
 *     }
 *     function preValidateSection(section) {
 *     }
 *     function validateDocument(errors, document) {
 *     }
 *     function validateSentence(errors, sentence) {
 *     }
 *     function validateSection(errors, section) {
 *     }
 * </pre>
 */
public class JavaScriptValidator extends Validator {
    private static final Logger LOG = LoggerFactory.getLogger(JavaScriptValidator.class);
    public final String DEFAULT_JS_VALIDATORS_PATH = "js";
    final List<Script> scripts = new ArrayList<>();

    @Override
    protected void init() throws RedPenException {
        String jsValidatorsPath = getConfigAttribute("script-path").orElse(DEFAULT_JS_VALIDATORS_PATH);
        File jsDirectory = new File(jsValidatorsPath);
        LOG.info("JavaScript validators directory: {}", jsDirectory.getAbsolutePath());
        if(!jsDirectory.exists()){
            jsDirectory.mkdir();
        }else {
            File[] jsValidatorFiles = jsDirectory.listFiles();
            if (jsValidatorFiles != null) {
                for (File file : jsValidatorFiles) {
                    if (file.isFile() && file.getName().endsWith(".js")) {
                        try {
                            scripts.add(new Script(this, file.getName(), loadCached(file)));
                        } catch (IOException e) {
                            LOG.error("Exception while reading js file", e);
                        }
                    }
                }
            }
        }
    }

    static final Map<File, String> fileCache = new HashMap<>();
    static final Map<File, Long> loadTime = new HashMap<>();

    /**
     * Load file content. Returns cached content if the last modified date is same as previous.
     *
     * @param file file to be loaded
     * @return file content
     * @throws IOException when failed to load the file
     */
    static String loadCached(File file) throws IOException {
        Objects.requireNonNull(file);
        Long storedTimestamp = loadTime.get(file);
        if (storedTimestamp != null && storedTimestamp == file.lastModified()) {
            return fileCache.get(file);
        }
        // file has been updated or has never been loaded
        String read = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())), Charset.forName("UTF-8"));
        fileCache.put(file, read);
        loadTime.put(file, file.lastModified());
        return read;
    }

    @Override
    public void preValidate(Sentence sentence) {
        for (Script js : scripts) {
            call(js, "preValidateSentence", sentence);
        }
    }

    @Override
    public void preValidate(Section section) {
        for (Script js : scripts) {
            call(js, "preValidateSection", section);
        }
    }

    @Override
    public void validate(List<ValidationError> errors, Document document) {
        for (Script js : scripts) {
            call(js, "validateDocument", errors, document);
        }
    }

    @Override
    public void validate(List<ValidationError> errors, Sentence sentence) {
        for (Script js : scripts) {
            call(js, "validateSentence", errors, sentence);
        }
    }

    @Override
    public void validate(List<ValidationError> errors, Section section) {
        for (Script js : scripts) {
            call(js, "validateSection", errors, section);
        }
    }

    private Map<Script, Map<String, Boolean>> functionExistenceMap = new HashMap<>();

   Script currentJS;

    void call(Script js, String functionName, Object... args) {
        this.currentJS = js;
        Map<String, Boolean> map = functionExistenceMap.computeIfAbsent(js, e -> new HashMap<>());
        Boolean functionExists = map
                .getOrDefault(functionName, true);
        if (functionExists) {
            try {
                js.invocable.invokeFunction(functionName, args);
            } catch (ScriptException e) {
                LOG.error("failed to invoke {}", functionName, e);
            } catch (NoSuchMethodException ignore) {
                map.put(functionName, false);
            }
        }
    }

    // give ValidationError factory methods public access so that they can be bound with JavaScript
    @Override
    public ValidationError createValidationError(Sentence sentenceWithError, Object... args) {
        return super.createValidationError(sentenceWithError, args);
    }

    @Override
    public ValidationError createValidationError(String messageKey, Sentence sentenceWithError, Object... args) {
        return super.createValidationError(messageKey, sentenceWithError, args);
    }

    @Override
    public ValidationError createValidationErrorFromToken(Sentence sentenceWithError, TokenElement token) {
        return super.createValidationErrorFromToken(sentenceWithError, token);
    }

    @Override
    public ValidationError createValidationErrorWithPosition(Sentence sentenceWithError,
                                                             Optional<LineOffset> start, Optional<LineOffset> end, Object... args) {
        return super.createValidationErrorWithPosition(sentenceWithError, start, end, args);
    }

    @Override
    protected String getLocalizedErrorMessage(Optional<String> key, Object... args) {
        String formatted;
        if (currentJS.message != null) {
            formatted = MessageFormat.format(currentJS.message, args);
        } else {
            formatted = super.getLocalizedErrorMessage(key, args);
        }
        return MessageFormat.format("[{0}] {1}",currentJS.name, formatted);
    }

    class Script {
        final String name;
        final Invocable invocable;
        final String message;
        ScriptEngineManager manager = new ScriptEngineManager();

        Script(JavaScriptValidator validator, String name, String script) throws RedPenException {
            this.name = name;
            ScriptEngine engine = manager.getEngineByName("nashorn");
            try {
                engine.put("redpenToBeBound", validator);
                engine.eval("var createValidationError = Function.prototype.bind.call(redpenToBeBound.createValidationError, redpenToBeBound);" +
                        "var createValidationErrorFromToken = Function.prototype.bind.call(redpenToBeBound.createValidationErrorFromToken, redpenToBeBound);" +
                        "var createValidationErrorWithPosition = Function.prototype.bind.call(redpenToBeBound.createValidationErrorWithPosition, redpenToBeBound);");

                CompiledScript compiledScript = ((Compilable) engine).compile(script);
                compiledScript.eval();
                this.message = (String)engine.get("message");
                this.invocable = (Invocable) engine;
            } catch (ScriptException e) {
                throw new RedPenException(e);
            }
        }
    }

}