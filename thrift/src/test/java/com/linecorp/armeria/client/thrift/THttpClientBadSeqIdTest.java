/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

import org.apache.thrift.TApplicationException;
import org.junit.Test;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

public class THttpClientBadSeqIdTest {

    @Test(timeout = 10000L)
    public void badSeqId() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            final THttpClient client = Clients.newClient(
                    "ttext+h1c://127.0.0.1:" + ss.getLocalPort(), THttpClient.class);
            final RpcResponse res = client.execute("/", HelloService.Iface.class, "hello", "trustin");
            assertThat(res.isDone()).isFalse();

            try (Socket s = ss.accept()) {
                final InputStream sin = s.getInputStream();
                final OutputStream sout = s.getOutputStream();

                // Ensure the request is received before sending its response.
                assertThat(sin.read()).isGreaterThanOrEqualTo(0);

                // Send the TTEXT over HTTP/1 response with mismatching seqid.
                final byte[] thriftTextResponse = ('{' +
                                                   "  \"method\": \"hello\"," +
                                                   "  \"type\": \"CALL\"," +
                                                   "  \"seqid\": " + Integer.MIN_VALUE + ',' +
                                                   "  \"args\": { \"success\": \"Hello, trustin!\" }" +
                                                   '}').getBytes(StandardCharsets.US_ASCII);
                sout.write(("HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Content-Length: " + thriftTextResponse.length + "\r\n" +
                            "\r\n").getBytes(StandardCharsets.US_ASCII));
                sout.write(thriftTextResponse);

                // Wait until the client closes the connection thanks to 'connection: close'.
                while (sin.read() >= 0) {
                    continue;
                }
            }

            assertThatThrownBy(res::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TApplicationException.class)
                    .satisfies(cause -> assertThat(((TApplicationException) cause.getCause()).getType())
                            .isEqualTo(TApplicationException.BAD_SEQUENCE_ID));
        }
    }
}
