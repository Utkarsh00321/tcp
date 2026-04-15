        import java.util.Arrays;

        public class Message {
            private int length;

            public byte getVersion() {
                return version;
            }

            public byte getType() {
                return type;
            }

            public byte[] getPayload() {
                final byte[] copyArray = Arrays.copyOf(payload, payload.length);
                return copyArray;
            }

            public int getLength() {
                return length;
            }

            private byte version;
            private byte type;
            private byte[] payload;

            Message(int length, byte version, byte type, byte[] payload) {
                this.length = length;
                this.version = version;
                this.type = type;
                this.payload = Arrays.copyOf(payload, payload.length);
            }
        }