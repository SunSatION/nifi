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
package org.apache.nifi.processors.aws.sqs;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.util.StandardValidators;

import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;

@SupportsBatching
@SeeAlso({ GetSQS.class, DeleteSQS.class })
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"Amazon", "AWS", "SQS", "Queue", "Put", "Publish"})
@CapabilityDescription("Publishes a message to an Amazon Simple Queuing Service Queue")
@DynamicProperty(name = "The name of a Message Attribute to add to the message", value = "The value of the Message Attribute",
        description = "Allows the user to add key/value pairs as Message Attributes by adding a property whose name will become the name of "
        + "the Message Attribute and value will become the value of the Message Attribute", supportsExpressionLanguage = true)
public class PutSQS extends AbstractSQSProcessor {

    public static final PropertyDescriptor DELAY = new PropertyDescriptor.Builder()
            .name("delay")
            .displayName("Delay")
            .description("The amount of time to delay the message before it becomes available to consumers")
            .required(false)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor MESSAGE_GROUP_ID = new PropertyDescriptor.Builder()
            .name("message-group-id")
            .displayName("Message Group ID")
            .description("You must provide a non-empty MessageGroupId when sending messages to a FIFO queue")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue(null)
            .expressionLanguageSupported(true)
            .build();

    public static final List<PropertyDescriptor> properties = Collections.unmodifiableList(
            Arrays.asList(QUEUE_URL, ACCESS_KEY, SECRET_KEY, CREDENTIALS_FILE, AWS_CREDENTIALS_PROVIDER_SERVICE, REGION, DELAY, TIMEOUT, PROXY_HOST, PROXY_HOST_PORT, MESSAGE_GROUP_ID));

    private volatile List<PropertyDescriptor> userDefinedProperties = Collections.emptyList();

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext context) {
        final List<ValidationResult> problems = new ArrayList<>(super.customValidate(context));
        final boolean delaySet = context.getProperty(DELAY).isSet();
        final boolean messageGroupId = context.getProperty(MESSAGE_GROUP_ID).isSet();

        if ( delaySet && messageGroupId )
            problems.add(new ValidationResult.Builder().subject("Combined Message Group Id / Delay").input("Message Group ID & Delay").valid(false).explanation("Message Group Id and Delay cannot be both set simultaneously").build());
        return problems;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .expressionLanguageSupported(true)
                .required(false)
                .dynamic(true)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .build();
    }

    @OnScheduled
    public void setup(final ProcessContext context) {
        userDefinedProperties = new ArrayList<>();
        for (final PropertyDescriptor descriptor : context.getProperties().keySet()) {
            if (descriptor.isDynamic()) {
                userDefinedProperties.add(descriptor);
            }
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final long startNanos = System.nanoTime();
        final AmazonSQSClient client = getClient();
        final SendMessageBatchRequest request = new SendMessageBatchRequest();
        final String queueUrl = context.getProperty(QUEUE_URL).evaluateAttributeExpressions(flowFile).getValue();
        request.setQueueUrl(queueUrl);

        final Set<SendMessageBatchRequestEntry> entries = new HashSet<>();

        final SendMessageBatchRequestEntry entry = new SendMessageBatchRequestEntry();
        entry.setId(flowFile.getAttribute("uuid"));
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        session.exportTo(flowFile, baos);
        final String flowFileContent = baos.toString();
        entry.setMessageBody(flowFileContent);

        final Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();

        for (final PropertyDescriptor descriptor : userDefinedProperties) {
            final MessageAttributeValue mav = new MessageAttributeValue();
            mav.setDataType("String");
            mav.setStringValue(context.getProperty(descriptor).evaluateAttributeExpressions(flowFile).getValue());
            messageAttributes.put(descriptor.getName(), mav);
        }

        entry.setMessageAttributes(messageAttributes);
        if ( context.getProperty(MESSAGE_GROUP_ID).isSet() )
            entry.setMessageGroupId(context.getProperty(MESSAGE_GROUP_ID).evaluateAttributeExpressions(flowFile).getValue());
        if ( context.getProperty(DELAY).isSet()  )
            entry.setDelaySeconds(context.getProperty(DELAY).asTimePeriod(TimeUnit.SECONDS).intValue());

        entries.add(entry);

        request.setEntries(entries);

        Exception error =null;
        try {
            SendMessageBatchResult x = client.sendMessageBatch(request);
        } catch (final Exception e) {
            error = e;
        }

        if ( error != null )
        {
            Map<String, String> attributes = new HashMap<>();
            attributes.put("sqs.error", error.getMessage().toString());
            flowFile = session.putAllAttributes(flowFile, attributes);
            getLogger().error("Failed to send messages to Amazon SQS due to {}; routing to failure", new Object[]{error});
            flowFile = session.penalize(flowFile);
            session.transfer(flowFile, REL_FAILURE);
            return;
        } else {
            getLogger().info("Successfully published message to Amazon SQS for {}", new Object[]{flowFile});
            session.transfer(flowFile, REL_SUCCESS);
            final long transmissionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            session.getProvenanceReporter().send(flowFile, queueUrl, transmissionMillis);
        }
    }
}
