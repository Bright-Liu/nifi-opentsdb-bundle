/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.opentsdb;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.*;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.commons.lang3.StringUtils.trimToEmpty;


/**
 * Description: Put OpenTSDB Http
 *
 * @author bright
 */
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@EventDriven
@Tags({"OpenTSDB", "put", "http"})
@CapabilityDescription("Writes the contents of a FlowFile to OpenTSDB, using the specified parameters such as " +
        "HTTP URL which will be connected to, including scheme (http, e.g.), host, and port. " +
        "The default port for the REST API is 4242.")
public class PutOpenTSDBHttp extends AbstractProcessor {

    private static final PropertyDescriptor HTTP_URL = new PropertyDescriptor.Builder()
            .name("http-url")
            .displayName("OpenTSDB URL")
            .description("OpenTSDB URL which will be connected to, including scheme (http, e.g.), host, and port. " +
                    "The default port for the REST API is 4242.")
            .required(true)
            .defaultValue("http://10.0.2.44:4242") // Just for test
            .addValidator(StandardValidators.URL_VALIDATOR)
            .expressionLanguageSupported(true)
            .build();

    private static final PropertyDescriptor CONNECT_TIMEOUT = new PropertyDescriptor.Builder()
            .name("OpenTSDB-http-connect-timeout")
            .displayName("Connection Timeout")
            .description("Max wait time for the connection to the OpenTSDB REST API.")
            .required(true)
            .defaultValue("5 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(true)
            .build();

    private static final PropertyDescriptor RESPONSE_TIMEOUT = new PropertyDescriptor.Builder()
            .name("OpenTSDB-http-response-timeout")
            .displayName("Response Timeout")
            .description("Max wait time for a response from the OpenTSDB REST API.")
            .required(true)
            .defaultValue("15 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .expressionLanguageSupported(true)
            .build();

    private static final Relationship REL_SUCCESS = new Relationship.Builder().name("Success")
            .description("All FlowFiles that are written to OpenTSDB are routed to this relationship").build();

    private static final Relationship REL_FAILURE = new Relationship.Builder().name("Failure")
            .description("All FlowFiles that cannot be written to OpenTSDB are routed to this relationship").build();

    private ComponentLog logger;

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    private final AtomicReference<OkHttpClient> okHttpClientAtomicReference = new AtomicReference<>();

    @Override
    protected void init(ProcessorInitializationContext context) {
        logger = getLogger();

        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(HTTP_URL);
        descriptors.add(CONNECT_TIMEOUT);
        descriptors.add(RESPONSE_TIMEOUT);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return this.descriptors;
    }

    @OnScheduled
    public void setup(ProcessContext context) {
        createOpenTSDBClient(context);
    }

    private void createOpenTSDBClient(ProcessContext context) throws ProcessException {
        okHttpClientAtomicReference.set(null);

        OkHttpClient.Builder okHttpClient = new OkHttpClient.Builder();

        // Set timeouts
        okHttpClient.connectTimeout((context.getProperty(CONNECT_TIMEOUT).evaluateAttributeExpressions().asTimePeriod(TimeUnit.MILLISECONDS).intValue()), TimeUnit.MILLISECONDS);
        okHttpClient.readTimeout(context.getProperty(RESPONSE_TIMEOUT).evaluateAttributeExpressions().asTimePeriod(TimeUnit.MILLISECONDS).intValue(), TimeUnit.MILLISECONDS);

        okHttpClientAtomicReference.set(okHttpClient.build());
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final String baseUrl = trimToEmpty(context.getProperty(HTTP_URL).evaluateAttributeExpressions().getValue());
        final URL url;
        try {
            url = new URL((baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "api/put?details");
        } catch (MalformedURLException mue) {
            throw new ProcessException(mue);
        }

        final StringBuilder content = new StringBuilder();
        session.read(flowFile, in -> content.append(IOUtils.toString(in, "UTF-8")));
        List<DataPoint> dataPoints = JSONObject.parseArray(content.toString(), DataPoint.class);

        final int size = 250;
        final int dataPointsSize = dataPoints.size();
        int index = 0;
        do {
            final int start = index * size;
            final int end = (index + 1) * size < dataPointsSize ? (index + 1) * size : dataPointsSize;
            final boolean isSuccess = putOpenTSDB(dataPoints.subList(start, end), url);
            logger.debug("From {} to {} is success: {}", new Object[]{start, end, isSuccess});
            if (!isSuccess) {
                session.transfer(flowFile, REL_FAILURE);
                return;
            }
            ++index;
        } while (size * index < dataPointsSize);

        session.transfer(flowFile, REL_SUCCESS);
    }

    /**
     * Put data points to OpenTSDB
     *
     * @param dataPoints data points
     * @param url        REST API URL
     * @return true or false
     */
    private boolean putOpenTSDB(List<DataPoint> dataPoints, URL url) {
        final RequestBody requestBody = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                JSONArray.toJSONString(dataPoints));

        final Response response;
        try {
            response = getResponse(url, "post", requestBody);
        } catch (final Exception e) {
            logger.error("Routing to {} due to exception: {}", new Object[]{REL_FAILURE.getName(), e});
            return false;
        }

        final int statusCode = response.code();
        if (statusCode / 100 == 2) {
            ResponseBody responseBody = response.body();
            try {
                String str = IOUtils.toString(responseBody.bytes(), "UTF-8");
                JSONObject result = JSONObject.parseObject(str);
                if (result.getInteger("success") == dataPoints.size()) {
                    return true;
                } else {
                    // Failed to write all data points to OpenTSDB and log the detail.
                    logger.error(str);
                }
            } catch (IOException e) {
                logger.error(e.getMessage());
            } finally {
                responseBody.close();
            }
        } else {  // 1xx, 3xx, 4xx, 5xx, etc.
            logger.warn("OpenTSDB returned code[{}] with message: {}, transferring flow file to failure", new Object[]{statusCode, response.message()});
        }
        return false;
    }

    private Response getResponse(URL url, String verb, RequestBody body) throws IOException {
        Request.Builder requestBuilder = new Request.Builder().url(url);

        if ("get".equalsIgnoreCase(verb)) {
            requestBuilder = requestBuilder.get();
        } else if ("put".equalsIgnoreCase(verb)) {
            requestBuilder = requestBuilder.put(body);
        } else if ("post".equalsIgnoreCase(verb)) {
            requestBuilder = requestBuilder.post(body);
        } else {
            throw new IllegalArgumentException("OpenTSDB REST API verb not supported by this processor: " + verb);
        }

        Request httpRequest = requestBuilder.build();

        logger.debug("Send OpenTSDB request to {}", new Object[]{url});

        Response response = okHttpClientAtomicReference.get().newCall(httpRequest).execute();

        int statusCode = response.code();

        if (statusCode == 0) {
            throw new IllegalStateException("Status code unknown, connection hasn't been attempted.");
        }

        logger.debug("Received response from OpenTSDB with status code {}", new Object[]{statusCode});

        return response;
    }
}
