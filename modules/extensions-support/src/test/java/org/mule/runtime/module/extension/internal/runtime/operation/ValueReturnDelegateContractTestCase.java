/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.operation;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mule.runtime.api.metadata.MediaType.ANY;
import static org.mule.runtime.core.message.NullAttributes.NULL_ATTRIBUTES;
import static org.mule.runtime.core.util.SystemUtils.getDefaultEncoding;
import static org.mule.tck.util.MuleContextUtils.eventBuilder;

import org.mule.runtime.api.message.Attributes;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.core.api.message.InternalMessage;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.module.extension.internal.runtime.ExecutionContextAdapter;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public abstract class ValueReturnDelegateContractTestCase extends AbstractMuleTestCase {

  @Mock(answer = RETURNS_DEEP_STUBS)
  protected MuleContext muleContext;

  @Mock
  protected ExecutionContextAdapter operationContext;

  protected Event event;

  @Mock
  protected Attributes attributes;

  protected ReturnDelegate delegate;

  @Before
  public void before() throws MuleException {
    event = eventBuilder().message(InternalMessage.builder().payload("").attributes(attributes).build()).build();
    delegate = createReturnDelegate();
    when(operationContext.getEvent()).thenReturn(event);
  }

  @Test
  public void returnsSingleValue() {
    byte[] value = new byte[] {};
    org.mule.runtime.api.message.MuleEvent result = delegate.asReturnValue(value, operationContext);

    Message message = getOutputMessage(result);

    assertThat(message.getPayload().getValue(), is(sameInstance(value)));
    assertThat(message.getPayload().getDataType().getType().equals(byte[].class), is(true));
  }

  @Test
  public void operationReturnsOperationResultButKeepsAttributes() throws Exception {
    Object payload = new Object();
    MediaType mediaType = ANY.withCharset(getDefaultEncoding(muleContext));

    org.mule.runtime.api.message.MuleEvent result =
        delegate.asReturnValue(Result.builder().output(payload).mediaType(mediaType).build(), operationContext);

    Message message = getOutputMessage(result);

    assertThat(message.getPayload().getValue(), is(sameInstance(payload)));
    assertThat(message.getAttributes(), is(NULL_ATTRIBUTES));
    assertThat(message.getPayload().getDataType().getMediaType(), equalTo(mediaType));
  }

  @Test
  public void operationReturnsOperationResultThatOnlySpecifiesPayload() throws Exception {
    Object payload = "hello world!";

    org.mule.runtime.api.message.MuleEvent result =
        delegate.asReturnValue(Result.builder().output(payload).build(), operationContext);

    Message message = getOutputMessage(result);

    assertThat(message.getPayload().getValue(), is(sameInstance(payload)));
    assertThat(message.getAttributes(), is(NULL_ATTRIBUTES));
    assertThat(message.getPayload().getDataType().getType().equals(String.class), is(true));
  }

  @Test
  public void operationReturnsOperationResultThatOnlySpecifiesPayloadAndAttributes() throws Exception {
    Object payload = "hello world!";
    Attributes newAttributes = mock(Attributes.class);

    org.mule.runtime.api.message.MuleEvent result =
        delegate.asReturnValue(Result.builder().output(payload).attributes(newAttributes).build(), operationContext);

    Message message = getOutputMessage(result);

    assertThat(message.getPayload().getValue(), is(sameInstance(payload)));
    assertThat(message.getAttributes(), is(sameInstance(newAttributes)));
    assertThat(message.getPayload().getDataType().getType().equals(String.class), is(true));
  }

  protected abstract ReturnDelegate createReturnDelegate();

  protected abstract Message getOutputMessage(org.mule.runtime.api.message.MuleEvent result);
}
