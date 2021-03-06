package com.github.ruediste.remoteJUnit.codeRunner;

import java.io.Serializable;
import java.util.Map;

class RemoteCodeRunnerRequestsAndResponses {

    /**
     * Sent to from the client to the server
     */
    public interface Request extends Serializable {
    }

    public static class RunCodeRequest implements Request {
        private static final long serialVersionUID = 1L;
        public byte[] code;
        public Map<String, byte[]> bootstrapClasses;

        RunCodeRequest(byte[] code, Map<String, byte[]> bootstrapClasses) {
            super();
            this.code = code;
            this.bootstrapClasses = bootstrapClasses;
        }
    }

    public static class CustomRequest implements Request {
        private static final long serialVersionUID = 1L;
        public long runId;
        public byte[] payload;

        CustomRequest(long runId, byte[] payload) {
            super();
            this.runId = runId;
            this.payload = payload;
        }

    }

    /**
     * Responses from the server to the client
     */
    public interface Response extends Serializable {
    }

    public static class FailureResponse implements Response {
        public FailureResponse(Exception e) {
            exception = e;
        }

        private static final long serialVersionUID = 1L;
        public Exception exception;
    }

    public static class CodeStartedResponse implements Response {
        public CodeStartedResponse(long runId) {
            this.runId = runId;
        }

        private static final long serialVersionUID = 1L;
        public long runId;
    }

    public static class CustomResponse implements Response {
        private static final long serialVersionUID = 1L;
        public byte[] payload;

        public CustomResponse(byte[] payload) {
            this.payload = payload;
        }

    }
}
