package com.github.ruediste.remoteJUnit.codeRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.ruediste.remoteJUnit.codeRunner.CodeRunnerClient.ClassMapBuilder;
import com.github.ruediste.remoteJUnit.codeRunner.CodeRunnerClient.RequestChannel;
import com.github.ruediste.remoteJUnit.codeRunner.CodeRunnerCommon.FailureResponse;

public class ClassLoadingRemoteCodeRunnerClient<TMessage> {
    private final static Logger log = LoggerFactory
            .getLogger(ClassLoadingRemoteCodeRunnerClient.class);

    private interface RemoteJUnitMessage extends Serializable {
    }

    public static class RequestClassMessage implements RemoteJUnitMessage {

        private static final long serialVersionUID = 1L;
        public String name;

        public RequestClassMessage(String name) {
            this.name = name;
        }

    }

    public static class ServerCodeExited implements RemoteJUnitMessage {
        private Throwable exception;

        public ServerCodeExited(Throwable e) {
            this.exception = e;
        }

        private static final long serialVersionUID = 1L;
    }

    public static class ServerCodeExitReceived implements RemoteJUnitMessage {
        private static final long serialVersionUID = 1L;
    }

    public static class SendClassMessage implements RemoteJUnitMessage {
        private static final long serialVersionUID = 1L;
        public byte[] data;
        public String name;

        public SendClassMessage(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

    }

    public static class CustomMessageWrapper implements RemoteJUnitMessage {
        private static final long serialVersionUID = 1L;

        byte[] message;

        public CustomMessageWrapper(byte[] message) {
            this.message = message;
        }

    }

    public interface RemoteJUnitResponse extends Serializable {

    }

    public static class EmptyResponse implements RemoteJUnitResponse {

        private static final long serialVersionUID = 1L;

    }

    public static class ToClientMessagesResponse implements RemoteJUnitResponse {
        private static final long serialVersionUID = 1L;
        public List<RemoteJUnitMessage> messages;

        public ToClientMessagesResponse(List<RemoteJUnitMessage> messages) {
            this.messages = messages;
        }

    }

    public interface RemoteJUnitRequest extends Serializable {
    }

    public static class GetToClientMessagesRequest implements
            RemoteJUnitRequest {
        private static final long serialVersionUID = 1L;

    }

    public static class SendToServerMessagesRequest implements
            RemoteJUnitRequest {
        private static final long serialVersionUID = 1L;

        public List<RemoteJUnitMessage> messages = new ArrayList<>();

        public SendToServerMessagesRequest(List<RemoteJUnitMessage> messages) {
            this.messages = messages;
        }

    }

    public interface RemoteCodeEnvironment<TMessage> {

        void sendToClient(TMessage msg);

        BlockingQueue<TMessage> getToServerMessages();

        ClassLoader getClassLoader();

    }

    private static class ClassLoadingRemoteCode<TMessage> implements
            CodeRunnerCommon.RemoteCode, RemoteCodeEnvironment<TMessage> {

        private static final long serialVersionUID = 1L;

        private static class SessionClassLoader extends ClassLoader {
            private static final Logger log = LoggerFactory
                    .getLogger(SessionClassLoader.class);
            static {
                registerAsParallelCapable();
            }
            private ClassLoadingRemoteCode<?> code;

            Map<String, byte[]> classData = new HashMap<String, byte[]>();

            SessionClassLoader(ClassLoadingRemoteCode<?> code,
                    ClassLoader parent) {
                super(parent);
                this.code = code;
            }

            @Override
            protected Class<?> findClass(String name)
                    throws ClassNotFoundException {
                byte[] data;
                synchronized (classData) {
                    data = classData.get(name);
                }
                if (data == null) {
                    log.debug("requesting from client: " + name);
                    // request class
                    try {
                        code.toClient.put(new RequestClassMessage(name));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    // wait for class to be returned
                    do {
                        synchronized (classData) {
                            data = classData.get(name);
                            if (data == null)
                                try {
                                    classData.wait();
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                        }
                    } while (data == null);
                    log.debug("loaded from client: " + name);
                }
                if (data.length == 0)
                    throw new ClassNotFoundException(name);
                else
                    return defineClass(name, data, 0, data.length);
            }

            public void addClass(SendClassMessage msg) {
                synchronized (classData) {
                    classData.put(msg.name, msg.data);
                    classData.notifyAll();
                }
            }
        }

        private SessionClassLoader classLoader;

        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }

        private final BlockingQueue<TMessage> toServer = new LinkedBlockingQueue<>();
        private final BlockingQueue<RemoteJUnitMessage> toClient = new LinkedBlockingQueue<RemoteJUnitMessage>();

        private Supplier<ClassLoader> parentClassLoaderSupplier;

        private byte[] codeDelegate;

        public ClassLoadingRemoteCode(
                Consumer<RemoteCodeEnvironment<TMessage>> codeDelegate) {
            this.codeDelegate = SerializationHelper.toByteArray(codeDelegate);

        }

        private ClassLoader getParentClassLoader() {
            if (parentClassLoaderSupplier == null)
                return getClass().getClassLoader();
            else
                return parentClassLoaderSupplier.get();
        }

        Semaphore exitConfirmationReceived = new Semaphore(0);

        @SuppressWarnings("unchecked")
        @Override
        public void run() {
            try {
                classLoader = new SessionClassLoader(this,
                        getParentClassLoader());
                ((Consumer<RemoteCodeEnvironment<TMessage>>) SerializationHelper
                        .toObject(codeDelegate, classLoader)).accept(this);
                toClient.add(new ServerCodeExited(null));
            } catch (Throwable e) {
                toClient.add(new ServerCodeExited(e));
            } finally {
                try {
                    exitConfirmationReceived.acquire();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public byte[] handle(byte[] requestBa) {
            RemoteJUnitRequest request = (RemoteJUnitRequest) SerializationHelper
                    .toObject(requestBa, getClass().getClassLoader());
            log.debug("handling " + request.getClass().getSimpleName());
            try {
                return SerializationHelper.toByteArray(handle(request));
            } catch (Exception e) {
                return SerializationHelper.toByteArray(new FailureResponse(e));
            } finally {
                log.debug("handling " + request.getClass().getSimpleName()
                        + " done");
            }
        }

        @SuppressWarnings("unchecked")
        private RemoteJUnitResponse handle(RemoteJUnitRequest request) {
            if (request instanceof GetToClientMessagesRequest) {
                List<RemoteJUnitMessage> messages = new ArrayList<>();
                try {
                    messages.add(toClient.take());
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                toClient.drainTo(messages);
                return new ToClientMessagesResponse(messages);
            } else if (request instanceof SendToServerMessagesRequest) {
                SendToServerMessagesRequest sendToServerMessagesRequest = (SendToServerMessagesRequest) request;
                List<TMessage> msgs = new ArrayList<>();
                for (RemoteJUnitMessage message : sendToServerMessagesRequest.messages) {
                    if (message instanceof ServerCodeExitReceived) {
                        exitConfirmationReceived.release();
                    } else if (message instanceof SendClassMessage) {
                        classLoader.addClass((SendClassMessage) message);
                    } else if (message instanceof CustomMessageWrapper) {
                        CustomMessageWrapper wrapper = (CustomMessageWrapper) message;
                        msgs.add((TMessage) SerializationHelper.toObject(
                                wrapper.message, classLoader));
                    } else
                        throw new UnsupportedOperationException(
                                "Unknown message " + message);
                }
                toServer.addAll(msgs);

                return new EmptyResponse();
            } else
                throw new UnsupportedOperationException(request.getClass()
                        .getName());

        }

        public Supplier<ClassLoader> getParentClassLoaderSupplier() {
            return parentClassLoaderSupplier;
        }

        public void setParentClassLoaderSupplier(
                Supplier<ClassLoader> parentClassLoaderSupplier) {
            this.parentClassLoaderSupplier = parentClassLoaderSupplier;
        }

        @Override
        public BlockingQueue<TMessage> getToServerMessages() {
            return toServer;
        }

        @Override
        public void sendToClient(TMessage msg) {
            toClient.add(new CustomMessageWrapper(SerializationHelper
                    .toByteArray(msg)));
        }
    }

    private final class ToServerSender implements Runnable {
        private BlockingQueue<RemoteJUnitMessage> toServer;
        private RequestChannel channel;

        public ToServerSender(BlockingQueue<RemoteJUnitMessage> toServer,
                RequestChannel channel) {
            this.toServer = toServer;
            this.channel = channel;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    List<RemoteJUnitMessage> messages = new ArrayList<>();
                    messages.add(toServer.take());
                    toServer.drainTo(messages);
                    channel.sendRequest(new SendToServerMessagesRequest(
                            messages));
                    // quit after the exit confirmation has been sent
                    if (messages.stream().anyMatch(
                            m -> m instanceof ServerCodeExitReceived)) {
                        break;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static class MessageChannel<TMessage> {
        private final BlockingQueue<TMessage> toClient = new LinkedBlockingQueue<>();
        private final BlockingQueue<RemoteJUnitMessage> toServer = new LinkedBlockingQueue<>();

        public BlockingQueue<TMessage> getToClient() {
            return toClient;
        }

        public void sendMessage(TMessage msg) {
            toServer.add(new CustomMessageWrapper(SerializationHelper
                    .toByteArray(msg)));
        }

    }

    private Supplier<ClassLoader> parentClassLoaderSupplier;
    private CodeRunnerClient client;

    @SuppressWarnings("unchecked")
    public void runCode(Consumer<RemoteCodeEnvironment<TMessage>> code,
            BiConsumer<TMessage, Consumer<TMessage>> clientMessageHandler) {
        ClassLoadingRemoteCode<TMessage> clCode = new ClassLoadingRemoteCode<>(
                code);
        clCode.setParentClassLoaderSupplier(parentClassLoaderSupplier);
        if (client == null) {
            client = new CodeRunnerClient();
        }
        RequestChannel channel = client.startCode(clCode, new ClassMapBuilder()
                .addClass(ClassLoadingRemoteCodeRunnerClient.class));

        MessageChannel<TMessage> msgChannel = new MessageChannel<>();

        // start client to server thread
        ToServerSender toServerSender = new ToServerSender(msgChannel.toServer,
                channel);
        new Thread(toServerSender).start();

        // process incoming messages
        messageProcessingLoop: while (true) {
            ToClientMessagesResponse messagesResponse = (ToClientMessagesResponse) channel
                    .sendRequest(new GetToClientMessagesRequest());
            for (RemoteJUnitMessage message : messagesResponse.messages) {
                if (message instanceof CustomMessageWrapper) {
                    clientMessageHandler
                            .accept((TMessage) SerializationHelper
                                    .toObject(((CustomMessageWrapper) message).message),
                                    msg -> msgChannel.sendMessage(msg));
                } else if (message instanceof ServerCodeExited) {
                    Throwable exception = ((ServerCodeExited) message).exception;
                    msgChannel.toServer.add(new ServerCodeExitReceived());
                    if (exception != null)
                        throw new RuntimeException("Error in server code",
                                exception);

                    break messageProcessingLoop;
                } else if (message instanceof RequestClassMessage) {
                    RequestClassMessage sendClassMessage = (RequestClassMessage) message;
                    log.debug("handling sendClassMessage for "
                            + sendClassMessage.name);
                    InputStream in = Thread
                            .currentThread()
                            .getContextClassLoader()
                            .getResourceAsStream(
                                    sendClassMessage.name.replace('.', '/')
                                            + ".class");
                    if (in == null) {
                        msgChannel.toServer.add(new SendClassMessage(
                                sendClassMessage.name, new byte[] {}));

                    } else {
                        ByteArrayOutputStream out = null;
                        try {
                            out = new ByteArrayOutputStream();
                            byte[] buffer = new byte[1024];
                            while (true) {
                                int read = in.read(buffer);
                                if (read < 0)
                                    break;
                                out.write(buffer, 0, read);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        } finally {
                            if (out != null)
                                try {
                                    out.close();
                                } catch (IOException e1) {
                                    // swallow
                                }
                            if (in != null)
                                try {
                                    in.close();
                                } catch (IOException e) {
                                    // swallow
                                }
                        }
                        msgChannel.toServer.add(new SendClassMessage(
                                sendClassMessage.name, out.toByteArray()));
                    }
                }
            }
        }
    }

    public Supplier<ClassLoader> getParentClassLoaderSupplier() {
        return parentClassLoaderSupplier;
    }

    public void setParentClassLoaderSupplier(
            Supplier<ClassLoader> parentClassLoaderSupplier) {
        this.parentClassLoaderSupplier = parentClassLoaderSupplier;
    }

    public CodeRunnerClient getClient() {
        return client;
    }

    public void setRunnerClient(CodeRunnerClient client) {
        this.client = client;
    }
}