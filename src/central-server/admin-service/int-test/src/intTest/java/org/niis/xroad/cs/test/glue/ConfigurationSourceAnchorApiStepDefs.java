/*
 * The MIT License
 * <p>
 * Copyright (c) 2019- Nordic Institute for Interoperability Solutions (NIIS)
 * Copyright (c) 2018 Estonian Information System Authority (RIA),
 * Nordic Institute for Interoperability Solutions (NIIS), Population Register Centre (VRK)
 * Copyright (c) 2015-2017 Estonian Information System Authority (RIA), Population Register Centre (VRK)
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.niis.xroad.cs.test.glue;

import io.cucumber.java.en.Step;
import org.assertj.core.api.Assertions;
import org.niis.xroad.cs.openapi.ConfigurationSourceAnchorsApi;
import org.niis.xroad.cs.openapi.model.ConfigurationTypeDto;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

import static com.nortal.test.asserts.Assertions.equalsAssertion;
import static com.nortal.test.asserts.Assertions.notNullAssertion;
import static org.springframework.http.HttpStatus.OK;

@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class ConfigurationSourceAnchorApiStepDefs extends BaseStepDefs {

    @Autowired
    private ConfigurationSourceAnchorsApi configurationSourceAnchorsApi;

    private ConfigurationTypeDto configurationTypeDto;
    private OffsetDateTime createdAt;
    private String hash;

    @Step("user gets the {string} configuration source anchor")
    public void getAnchor(String configType) {
        configurationTypeDto = ConfigurationTypeDto.fromValue(configType.toUpperCase());
        final var response = configurationSourceAnchorsApi.getAnchor(configurationTypeDto);

        validate(response)
                .assertion(equalsStatusCodeAssertion(OK))
                .assertion(notNullAssertion("body.anchor"))
                .assertion(notNullAssertion("body.anchor.createdAt"))
                .assertion(notNullAssertion("body.anchor.hash"))
                .execute();

        createdAt = response.getBody().getAnchor().getCreatedAt();
        hash = response.getBody().getAnchor().getHash();
    }

    @Step("user recreates the {string} configuration source anchor")
    public void recreateAnchor(String configType) {
        configurationTypeDto = ConfigurationTypeDto.fromValue(configType.toUpperCase());
        final var response = configurationSourceAnchorsApi.reCreateAnchor(configurationTypeDto);

        validate(response)
                .assertion(equalsStatusCodeAssertion(OK))
                .assertion(notNullAssertion("body.createdAt"))
                .assertion(notNullAssertion("body.hash"))
                .execute();

        Assertions.assertThat(response.getBody().getCreatedAt()).isNotEqualTo(createdAt);
        Assertions.assertThat(response.getBody().getHash()).isNotEqualTo(hash);

        createdAt = response.getBody().getCreatedAt();
        hash = response.getBody().getHash();
    }

    @Step("recreated anchor matches returned from GET API")
    public void matchesDataFromGet() {
        final var response = configurationSourceAnchorsApi.getAnchor(configurationTypeDto);

        validate(response)
                .assertion(equalsStatusCodeAssertion(OK))
                .assertion(equalsAssertion(createdAt, "body.anchor.createdAt", "must be same as from previous step"))
                .assertion(equalsAssertion(hash, "body.anchor.hash", "must be same as from previous step"))
                .execute();
    }

    @Step("recreated anchor is downloaded and filename matches {string}")
    public void downloadRecreatedAnchor(String filename) {
        final var response = configurationSourceAnchorsApi.downloadAnchor(configurationTypeDto);

        validate(response)
                .assertion(equalsStatusCodeAssertion(OK))
                .assertion(equalsAssertion(createdAt, "body.filename", "must match"))
                .assertion(equalsAssertion(hash, "body.anchor.hash", "must be same as from previous step"))
                .execute();
    }
}
