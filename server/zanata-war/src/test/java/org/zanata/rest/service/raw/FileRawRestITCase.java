/*
 * Copyright 2010, Red Hat, Inc. and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.zanata.rest.service.raw;

import java.io.*;
import java.lang.annotation.Annotation;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.common.base.Optional;
import org.apache.commons.codec.binary.Hex;
import org.dbunit.operation.DatabaseOperation;
import org.fedorahosted.tennera.jgettext.Message;
import org.fedorahosted.tennera.jgettext.catalog.parse.MessageStreamParser;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;
import org.junit.Test;
import org.zanata.RestTest;
import org.zanata.common.LocaleId;
import org.zanata.rest.DocumentFileUploadForm;
import org.zanata.rest.ResourceRequest;
import org.zanata.rest.dto.ChunkUploadResponse;
import org.zanata.rest.dto.resource.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.zanata.provider.DBUnitProvider.DataSetOperation;
import static org.zanata.util.RawRestTestUtils.assertHeaderValue;

public class FileRawRestITCase extends RestTest {

    private final Annotation[] multipartFormAnnotations =
            { new MultipartFormLiteral() };

    @Override
    protected void prepareDBUnitOperations() {
        addBeforeTestOperation(new DataSetOperation(
                "org/zanata/test/model/AccountData.dbunit.xml",
                DatabaseOperation.CLEAN_INSERT));
        addBeforeTestOperation(new DataSetOperation(
                "org/zanata/test/model/ProjectsData.dbunit.xml",
                DatabaseOperation.CLEAN_INSERT));
        addBeforeTestOperation(new DataSetOperation(
                "org/zanata/test/model/LocalesData.dbunit.xml",
                DatabaseOperation.CLEAN_INSERT));
        addBeforeTestOperation(new DataSetOperation(
                "org/zanata/test/model/TextFlowTestData.dbunit.xml",
                DatabaseOperation.CLEAN_INSERT));
    }

    @Test
    @RunAsClient
    public void getPo() throws Exception {
        new ResourceRequest(
                getRestEndpointUrl("/file/translation/sample-project/1.0/en-US/po"),
                "GET", getAuthorizedEnvironment()) {
            @Override
            protected Invocation.Builder prepareRequest(
                    ResteasyWebTarget webTarget) {
                return webTarget.queryParam("docId", "my/path/document.txt")
                        .request();
            }

            @Override
            protected void onResponse(Response response) {
                // Ok
                assertThat(response.getStatus()).isEqualTo(200);
                assertHeaderValue(response, "Content-Disposition",
                        "attachment; filename=\"document.txt.po\"");
                assertHeaderValue(response, HttpHeaders.CONTENT_TYPE,
                        MediaType.TEXT_PLAIN);

                String entityString = response.readEntity(String.class);

                assertPoFileCorrect(entityString);
                assertPoFileContainsTranslations(entityString, "hello world",
                        "");
            }
        }.run();
    }

    private DocumentFileUploadForm generateUploadForm(boolean isFirst,
                                                      boolean isLast, String fileType, String md5hash, long streamSize,
                                                      InputStream fileStream) {
        DocumentFileUploadForm uploadForm = new DocumentFileUploadForm();
        uploadForm.setFirst(isFirst);
        uploadForm.setLast(isLast);
        uploadForm.setFileType(fileType);
        uploadForm.setHash(md5hash);
        uploadForm.setSize(streamSize);
        uploadForm.setFileStream(fileStream);
        return uploadForm;
    }

    @Test
    @RunAsClient
    public void getBaked() throws Exception {
        //file/source/{projectSlug}/{iterationSlug}?docId={docId}
        new ResourceRequest(
                getRestEndpointUrl("/file/source/sample-project/1.0"),
                "POST", getAuthorizedEnvironment()) {
            @Override
            protected Invocation.Builder prepareRequest(
                    ResteasyWebTarget webTarget) {
                return webTarget.queryParam("docId", "test-gettext.pot")
                        .request(MediaType.APPLICATION_XML_TYPE);
            }

            @Override
            public void invoke(Invocation.Builder builder) throws Exception {
                MultipartFormDataOutput mdo = new MultipartFormDataOutput();
                File file = new File("src/test/resources/org/zanata/test-gettext.pot");
                final FileInputStream fileInputStream = new FileInputStream(file);

                String hash = calculateFileHash(file);

//                mdo.addFormData("file", fileInputStream, MediaType.APPLICATION_OCTET_STREAM_TYPE);
//                mdo.addFormData("type", "PO", MediaType.TEXT_PLAIN_TYPE);
//                mdo.addFormData("first", "true", MediaType.TEXT_PLAIN_TYPE);
//                mdo.addFormData("last", "true", MediaType.TEXT_PLAIN_TYPE);
//                mdo.addFormData("size", file.length(), MediaType.TEXT_PLAIN_TYPE);
//                mdo.addFormData("hash", hash, MediaType.TEXT_PLAIN_TYPE);
//
//                GenericEntity<MultipartFormDataOutput> entity = new GenericEntity<MultipartFormDataOutput>(mdo) {};
//
//                Response response = builder.buildPost(Entity.entity(entity, MediaType.MULTIPART_FORM_DATA_TYPE)).invoke();

                DocumentFileUploadForm fileUploadForm = generateUploadForm(true, true, "PO", hash, file.length(), fileInputStream);

                Response response = builder.post(Entity.entity(fileUploadForm,
                        MediaType.MULTIPART_FORM_DATA_TYPE, multipartFormAnnotations));

                try {
                    onResponse(response);
                } finally {
                    response.close();
                }
            }

            @Override
            protected void onResponse(Response response) {
                ChunkUploadResponse chunkUploadResponse = response.readEntity(ChunkUploadResponse.class);

                // Ok
                assertThat(response.getStatus()).isEqualTo(200);
            }
        }.run();

        new ResourceRequest(
                getRestEndpointUrl("/file/translation/sample-project/1.0/en-US/baked"),
                "GET", getAuthorizedEnvironment()) {
            @Override
            protected Invocation.Builder prepareRequest(
                    ResteasyWebTarget webTarget) {
                return webTarget.queryParam("docId", "test-gettext.pot")
                        .request();
            }

            @Override
            protected void onResponse(Response response) {
                // Ok
                assertThat(response.getStatus()).isEqualTo(200);
                assertHeaderValue(response, "Content-Disposition",
                        "attachment; filename=\"test-gettext.pot\"");
                assertHeaderValue(response, HttpHeaders.CONTENT_TYPE,
                        MediaType.TEXT_PLAIN);

                String entityString = response.readEntity(String.class);

                assertPoFileCorrect(entityString);
                assertPoFileContainsTranslations(entityString, "hello world",
                        "");
            }
        }.run();
    }

    private String calculateFileHash(File srcFile) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            InputStream fileStream = new FileInputStream(srcFile);
            try {
                fileStream = new DigestInputStream(fileStream, md);
                byte[] buffer = new byte[256];
                //noinspection StatementWithEmptyBody
                while (fileStream.read(buffer) > 0) {
                    // just keep digesting the input
                }
            } finally {
                fileStream.close();
            }
            //noinspection UnnecessaryLocalVariable
            String md5hash = new String(Hex.encodeHex(md.digest()));
            return md5hash;
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @RunAsClient
    public void getBakedWithMinContentState() throws Exception {
        new ResourceRequest(
                getRestEndpointUrl("/file/translation/sample-project/1.0/en-US/baked"),
                "GET", getAuthorizedEnvironment()) {
            @Override
            protected Invocation.Builder prepareRequest(
                    ResteasyWebTarget webTarget) {
                return webTarget.queryParam("docId", "my/path/document.txt")
                                .queryParam("minContentState", "Accepted")
                        .request();
            }

            @Override
            protected void onResponse(Response response) {
                // Ok
                assertThat(response.getStatus()).isEqualTo(200);
                assertHeaderValue(response, "Content-Disposition",
                        "attachment; filename=\"document.txt\"");
                assertHeaderValue(response, HttpHeaders.CONTENT_TYPE,
                        MediaType.TEXT_PLAIN);

                String entityString = response.readEntity(String.class);

                assertPoFileCorrect(entityString);
                assertPoFileContainsTranslations(entityString, "hello world",
                        "");
            }
        }.run();
    }

    @Test
    @RunAsClient
    public void getPo2() throws Exception {
        new ResourceRequest(
                getRestEndpointUrl("/file/translation/sample-project/1.0/en-US/po"),
                "GET", getAuthorizedEnvironment()) {
            @Override
            protected Invocation.Builder prepareRequest(
                    ResteasyWebTarget webTarget) {
                return webTarget.queryParam("docId", "my/path/document-2.txt")
                        .request();
            }

            @Override
            protected void onResponse(Response response) {
                // Ok
                assertThat(response.getStatus()).isEqualTo(200);
                assertHeaderValue(response, "Content-Disposition",
                        "attachment; filename=\"document-2.txt.po\"");
                assertHeaderValue(response, HttpHeaders.CONTENT_TYPE,
                        MediaType.TEXT_PLAIN);
                String entityString = response.readEntity(String.class);
                assertPoFileCorrect(entityString);
                assertPoFileContainsTranslations(
                        entityString, "mssgId1",
                        "mssgTrans1", "mssgId2", "mssgTrans2", "mssgId3",
                        "mssgTrans3");
            }
        }.run();
    }

    private static void assertPoFileCorrect(String poFileContents) {
        MessageStreamParser messageParser =
                new MessageStreamParser(new StringReader(poFileContents));

        while (messageParser.hasNext()) {
            Message message = messageParser.next();

            if (message.isHeader()) {
                // assert that expected headers are present (with values if
                // needed)
                assertThat(message.getMsgstr())
                        .contains("MIME-Version:", "Content-Type:",
                                "Content-Transfer-Encoding:",
                                "Last-Translator:", "PO-Revision-Date:",
                                "X-Generator: Zanata", "Plural-Forms:");
            }
        }
    }

    /**
     * Validates that the po files contains the appropriate translations.
     *
     * @param poFileContents
     *            The contents of the PO file as a string
     * @param translations
     *            The translations in (msgid, msgstr) pairs. E.g. mssgid1,
     *            trans1, mssgid2, trans2, ... etc.
     */
    private static void assertPoFileContainsTranslations(String poFileContents,
            String... translations) {
        if (translations.length % 2 != 0) {
            throw new AssertionError(
                    "Translation parameters should be given in pairs.");
        }

        MessageStreamParser messageParser =
                new MessageStreamParser(new StringReader(poFileContents));

        List<String> found = new ArrayList<String>(translations.length);

        // Assert that all the given translations are present
        while (messageParser.hasNext()) {
            Message message = messageParser.next();

            if (!message.isHeader()) {
                // Find the message id in the array given to check
                int foundAt = 0;
                while (foundAt < translations.length) {
                    // Message Id found
                    if (message.getMsgid().equals(translations[foundAt])) {
                        found.add(message.getMsgid());
                        // Translation does not match
                        if (!message.getMsgstr().equals(
                                translations[foundAt + 1])) {
                            throw new AssertionError(
                                    "Expected translation for mssgid '"
                                            + message.getMsgid() + "' "
                                            + "is: '"
                                            + translations[foundAt + 1] + "'. "
                                            + "Instead got '"
                                            + message.getMsgstr() + "'");
                        }
                    }

                    foundAt += 2;
                }
            }
        }

        // If there are some messages not found
        if (found.size() < translations.length / 2) {
            StringBuilder assertionError =
                    new StringBuilder(
                            "The following mssgids were expected yet not found: ");
            for (int i = 0; i < translations.length; i += 2) {
                if (!found.contains(translations[i])) {
                    assertionError.append(translations[i] + " | ");
                }
            }

            throw new AssertionError(assertionError.toString());
        }
    }

    @SuppressWarnings("all")
    private static class MultipartFormLiteral implements MultipartForm {

        @Override
        public java.lang.Class<? extends Annotation> annotationType() {
            return MultipartForm.class;
        }
    }

}
