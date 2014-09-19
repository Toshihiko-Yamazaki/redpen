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
package cc.redpen.validator;

import cc.redpen.RedPenException;
import cc.redpen.ValidationError;
import cc.redpen.model.Sentence;
import org.junit.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class ValidatorTest {
    @Test
    public void testValidationErrorCreation() throws RedPenException {
        ValidationErrorMessageTest validationErrorMessageTest = new ValidationErrorMessageTest();
        validationErrorMessageTest.preInit(null, null);
        List<ValidationError> validationErrors = validationErrorMessageTest.validate(new Sentence("sentence", 1));
        assertEquals("error str:string 1:1 2:2 3:3", validationErrors.get(0).getMessage());

        validationErrorMessageTest.setLocale(Locale.JAPAN);
        validationErrors = validationErrorMessageTest.validate(new Sentence("sentence", 1));
        assertEquals("エラー ストリング:string 1:1 2:2 3:3", validationErrors.get(0).getMessage());

    }
}

