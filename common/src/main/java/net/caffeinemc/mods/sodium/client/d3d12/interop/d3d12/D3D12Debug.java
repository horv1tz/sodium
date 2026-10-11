package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.ComObject;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12InfoQueueFilter;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12InfoQueueFilterDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Message;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3D12Debug: switches the D3D12 debug layer on. It has to be used <b>before</b> the device is created
 * ({@link D3D12#enableDebugLayer()} does the whole sequence); afterwards the device exposes the layer's messages
 * through {@link D3D12Device#getInfoQueue()}.
 *
 * <p>The debug layer is an optional Windows feature ("Graphics Tools"). When it is missing,
 * {@link D3D12#tryGetDebugInterface()} returns {@code null} and device creation proceeds without validation.
 *
 * <p>Thread safety: call during start-up from a single thread.
 */
public final class D3D12Debug extends ComObject {
    private static final MethodHandle VOID_NOARGS = Com.method(FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle VOID_BOOL = Com.method(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    public D3D12Debug(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Enables the debug layer for devices created after this call.
     */
    public void enableDebugLayer() {
        try {
            VOID_NOARGS.invokeExact(this.fn(Vtbl.ID3D12Debug.ENABLE_DEBUG_LAYER), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Enables or disables GPU-based validation (slow; instruments shaders) through {@code ID3D12Debug1}. Must be called
     * before the device is created.
     *
     * @return false if the runtime does not offer {@code ID3D12Debug1} (the setting was not applied)
     */
    public boolean setEnableGPUBasedValidation(boolean enable) {
        return this.callDebug1(Vtbl.ID3D12Debug1.SET_ENABLE_GPU_BASED_VALIDATION, enable);
    }

    /**
     * Enables or disables validation that synchronises command queues, through {@code ID3D12Debug1}. Must be called
     * before the device is created.
     *
     * @return false if the runtime does not offer {@code ID3D12Debug1} (the setting was not applied)
     */
    public boolean setEnableSynchronizedCommandQueueValidation(boolean enable) {
        return this.callDebug1(Vtbl.ID3D12Debug1.SET_ENABLE_SYNCHRONIZED_COMMAND_QUEUE_VALIDATION, enable);
    }

    private boolean callDebug1(int slot, boolean enable) {
        Debug1 debug1 = this.tryQueryInterface(Iids.ID3D12Debug1, Debug1::new);

        if (debug1 == null) {
            return false;
        }

        try (debug1) {
            VOID_BOOL.invokeExact(debug1.slot(slot), debug1.ptr(), enable ? 1 : 0);

            return true;
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    private static final class Debug1 extends ComObject {
        Debug1(MemorySegment pointer) {
            super(pointer);
        }

        MemorySegment slot(int slot) {
            return this.fn(slot);
        }
    }

    /**
     * ID3D12InfoQueue: the stored debug layer messages of a device, obtained with {@link D3D12Device#getInfoQueue()}.
     * The typical use is to drain it once per frame and forward the messages to the game's log:
     *
     * <pre>{@code
     * for (D3D12Debug.InfoQueue.Message m : queue.drainMessages()) {
     *     log(m.severity(), m.description());
     * }
     * }</pre>
     *
     * <p>Thread safety: free-threaded (the debug layer serialises the calls itself).
     */
    public static final class InfoQueue extends ComObject {
        private static final MethodHandle SET_MESSAGE_COUNT_LIMIT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        private static final MethodHandle CLEAR = Com.method(FunctionDescriptor.ofVoid(ADDRESS));
        private static final MethodHandle GET_MESSAGE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        private static final MethodHandle GET_COUNT = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        private static final MethodHandle HR_FILTER = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle HR_NOARGS = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle INT_NOARGS = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle HR_INT_BOOL = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        private static final MethodHandle INT_INT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle VOID_NOARGS = Com.method(FunctionDescriptor.ofVoid(ADDRESS));
        private static final MethodHandle VOID_BOOL = Com.method(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
        private static final MethodHandle ADD_MESSAGE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle ADD_APPLICATION_MESSAGE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

        public InfoQueue(MemorySegment pointer) {
            super(pointer);
        }

        /**
         * One debug layer message.
         *
         * @param category    {@code D3D12_MESSAGE_CATEGORY} ({@code Enums.MessageCategory})
         * @param severity    {@code D3D12_MESSAGE_SEVERITY} ({@code Enums.MessageSeverity}: CORRUPTION, ERROR, WARNING, INFO, MESSAGE)
         * @param id          {@code D3D12_MESSAGE_ID} ({@code Enums.MessageId})
         * @param description the human readable text
         */
        public record Message(int category, int severity, int id, String description) {
        }

        /**
         * A set of message categories, severities and ids for the storage filters. Any of the arrays may be
         * {@code null}.
         */
        public record Filter(int[] categories, int[] severities, int[] ids) {
            public static Filter ofIds(int... ids) {
                return new Filter(null, null, ids);
            }

            public static Filter ofSeverities(int... severities) {
                return new Filter(null, severities, null);
            }

            public static Filter ofCategories(int... categories) {
                return new Filter(categories, null, null);
            }
        }

        /**
         * @param limit the maximum number of stored messages; older messages are discarded beyond it
         */
        public void setMessageCountLimit(long limit) {
            try {
                int hr = (int) SET_MESSAGE_COUNT_LIMIT.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.SET_MESSAGE_COUNT_LIMIT), this.ptr(), limit);
                Hr.check(hr, "ID3D12InfoQueue::SetMessageCountLimit");
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        public void clearStoredMessages() {
            try {
                VOID_NOARGS.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.CLEAR_STORED_MESSAGES), this.ptr());
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        private long count(int slot) {
            try {
                return (long) GET_COUNT.invokeExact(this.fn(slot), this.ptr());
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        /**
         * @return the number of messages currently stored
         */
        public long getNumStoredMessages() {
            return this.count(Vtbl.ID3D12InfoQueue.GET_NUM_STORED_MESSAGES);
        }

        public long getNumStoredMessagesAllowedByRetrievalFilter() {
            return this.count(Vtbl.ID3D12InfoQueue.GET_NUM_STORED_MESSAGES_ALLOWED_BY_RETRIEVAL_FILTER);
        }

        public long getNumMessagesAllowedByStorageFilter() {
            return this.count(Vtbl.ID3D12InfoQueue.GET_NUM_MESSAGES_ALLOWED_BY_STORAGE_FILTER);
        }

        public long getNumMessagesDeniedByStorageFilter() {
            return this.count(Vtbl.ID3D12InfoQueue.GET_NUM_MESSAGES_DENIED_BY_STORAGE_FILTER);
        }

        public long getNumMessagesDiscardedByMessageCountLimit() {
            return this.count(Vtbl.ID3D12InfoQueue.GET_NUM_MESSAGES_DISCARDED_BY_MESSAGE_COUNT_LIMIT);
        }

        public long getMessageCountLimit() {
            return this.count(Vtbl.ID3D12InfoQueue.GET_MESSAGE_COUNT_LIMIT);
        }

        /**
         * Reads one stored message (index 0 is the oldest). Allocates temporary native memory.
         *
         * @throws D3D12Exception if the index is out of range
         */
        public Message getMessage(long index) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment length = arena.allocate(JAVA_LONG);

                // first ask for the size of the message, then fetch it
                int hr = (int) GET_MESSAGE.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.GET_MESSAGE), this.ptr(), index, MemorySegment.NULL, length);
                Hr.check(hr, "ID3D12InfoQueue::GetMessage(size)");

                MemorySegment buffer = arena.allocate(Math.max(length.get(JAVA_LONG, 0), D3D12Message.SIZE), 8);
                hr = (int) GET_MESSAGE.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.GET_MESSAGE), this.ptr(), index, buffer, length);
                Hr.check(hr, "ID3D12InfoQueue::GetMessage");

                long textLength = D3D12Message.getDescriptionByteLength(buffer);
                String text = "";

                if (textLength > 0) {
                    text = D3D12Message.getPDescription(buffer).reinterpret(textLength).getString(0, StandardCharsets.UTF_8);
                }

                return new Message(D3D12Message.getCategory(buffer), D3D12Message.getSeverity(buffer), D3D12Message.getID(buffer), text);
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        /**
         * Reads all stored messages (oldest first) and then clears the queue.
         */
        public List<Message> drainMessages() {
            long count = this.getNumStoredMessages();
            List<Message> messages = new ArrayList<>((int) Math.min(count, 1024));

            for (long i = 0; i < count; i++) {
                messages.add(this.getMessage(i));
            }

            this.clearStoredMessages();

            return messages;
        }

        /**
         * Adds entries to the storage filter, which decides which messages are stored in the first place.
         *
         * @param allow messages to allow, or {@code null}
         * @param deny  messages to deny, or {@code null}
         */
        public void addStorageFilterEntries(Filter allow, Filter deny) {
            try (Arena arena = Arena.ofConfined()) {
                int hr = (int) HR_FILTER.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.ADD_STORAGE_FILTER_ENTRIES), this.ptr(), buildFilter(arena, allow, deny));
                Hr.check(hr, "ID3D12InfoQueue::AddStorageFilterEntries");
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        /**
         * Pushes a new storage filter on the filter stack; undo with {@link #popStorageFilter()}.
         */
        public void pushStorageFilter(Filter allow, Filter deny) {
            try (Arena arena = Arena.ofConfined()) {
                int hr = (int) HR_FILTER.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.PUSH_STORAGE_FILTER), this.ptr(), buildFilter(arena, allow, deny));
                Hr.check(hr, "ID3D12InfoQueue::PushStorageFilter");
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        public void pushEmptyStorageFilter() {
            this.hrCall(Vtbl.ID3D12InfoQueue.PUSH_EMPTY_STORAGE_FILTER, "ID3D12InfoQueue::PushEmptyStorageFilter");
        }

        public void pushCopyOfStorageFilter() {
            this.hrCall(Vtbl.ID3D12InfoQueue.PUSH_COPY_OF_STORAGE_FILTER, "ID3D12InfoQueue::PushCopyOfStorageFilter");
        }

        private void hrCall(int slot, String what) {
            try {
                int hr = (int) HR_NOARGS.invokeExact(this.fn(slot), this.ptr());
                Hr.check(hr, what);
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        public void popStorageFilter() {
            try {
                VOID_NOARGS.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.POP_STORAGE_FILTER), this.ptr());
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        public void clearStorageFilter() {
            try {
                VOID_NOARGS.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.CLEAR_STORAGE_FILTER), this.ptr());
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        public int getStorageFilterStackSize() {
            try {
                return (int) INT_NOARGS.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.GET_STORAGE_FILTER_STACK_SIZE), this.ptr());
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        private static MemorySegment buildFilter(Arena arena, Filter allow, Filter deny) {
            MemorySegment filter = D3D12InfoQueueFilter.allocate(arena);
            fillList(arena, D3D12InfoQueueFilter.sliceAllowList(filter), allow);
            fillList(arena, D3D12InfoQueueFilter.sliceDenyList(filter), deny);

            return filter;
        }

        private static void fillList(Arena arena, MemorySegment list, Filter filter) {
            if (filter == null) {
                return;
            }

            if (filter.categories() != null && filter.categories().length > 0) {
                D3D12InfoQueueFilterDesc.setNumCategories(list, filter.categories().length);
                D3D12InfoQueueFilterDesc.setPCategoryList(list, arena.allocateFrom(JAVA_INT, filter.categories()));
            }

            if (filter.severities() != null && filter.severities().length > 0) {
                D3D12InfoQueueFilterDesc.setNumSeverities(list, filter.severities().length);
                D3D12InfoQueueFilterDesc.setPSeverityList(list, arena.allocateFrom(JAVA_INT, filter.severities()));
            }

            if (filter.ids() != null && filter.ids().length > 0) {
                D3D12InfoQueueFilterDesc.setNumIDs(list, filter.ids().length);
                D3D12InfoQueueFilterDesc.setPIDList(list, arena.allocateFrom(JAVA_INT, filter.ids()));
            }
        }

        /**
         * Makes the debug layer break into an attached debugger on messages of this severity.
         *
         * @param severity a {@code D3D12_MESSAGE_SEVERITY} ({@code Enums.MessageSeverity})
         */
        public void setBreakOnSeverity(int severity, boolean enable) {
            this.setBreak(Vtbl.ID3D12InfoQueue.SET_BREAK_ON_SEVERITY, severity, enable, "SetBreakOnSeverity");
        }

        /**
         * @param category a {@code D3D12_MESSAGE_CATEGORY} ({@code Enums.MessageCategory})
         */
        public void setBreakOnCategory(int category, boolean enable) {
            this.setBreak(Vtbl.ID3D12InfoQueue.SET_BREAK_ON_CATEGORY, category, enable, "SetBreakOnCategory");
        }

        /**
         * @param id a {@code D3D12_MESSAGE_ID} ({@code Enums.MessageId})
         */
        public void setBreakOnId(int id, boolean enable) {
            this.setBreak(Vtbl.ID3D12InfoQueue.SET_BREAK_ON_ID, id, enable, "SetBreakOnID");
        }

        private void setBreak(int slot, int value, boolean enable, String what) {
            try {
                int hr = (int) HR_INT_BOOL.invokeExact(this.fn(slot), this.ptr(), value, enable ? 1 : 0);
                Hr.check(hr, "ID3D12InfoQueue::" + what);
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        public boolean getBreakOnSeverity(int severity) {
            return this.getBreak(Vtbl.ID3D12InfoQueue.GET_BREAK_ON_SEVERITY, severity);
        }

        public boolean getBreakOnCategory(int category) {
            return this.getBreak(Vtbl.ID3D12InfoQueue.GET_BREAK_ON_CATEGORY, category);
        }

        public boolean getBreakOnId(int id) {
            return this.getBreak(Vtbl.ID3D12InfoQueue.GET_BREAK_ON_ID, id);
        }

        private boolean getBreak(int slot, int value) {
            try {
                return (int) INT_INT.invokeExact(this.fn(slot), this.ptr(), value) != 0;
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        /**
         * @param mute true to stop the layer from also writing messages to the debugger output
         */
        public void setMuteDebugOutput(boolean mute) {
            try {
                VOID_BOOL.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.SET_MUTE_DEBUG_OUTPUT), this.ptr(), mute ? 1 : 0);
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        public boolean getMuteDebugOutput() {
            try {
                return (int) INT_NOARGS.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.GET_MUTE_DEBUG_OUTPUT), this.ptr()) != 0;
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        /**
         * Adds a message with an application-chosen text to the queue.
         *
         * @param severity    a {@code D3D12_MESSAGE_SEVERITY}
         * @param description the text
         */
        public void addApplicationMessage(int severity, String description) {
            try (Arena arena = Arena.ofConfined()) {
                int hr = (int) ADD_APPLICATION_MESSAGE.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.ADD_APPLICATION_MESSAGE), this.ptr(),
                        severity, arena.allocateFrom(description, StandardCharsets.UTF_8));
                Hr.check(hr, "ID3D12InfoQueue::AddApplicationMessage");
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }

        /**
         * Adds a message with an explicit category, severity and id to the queue.
         */
        public void addMessage(int category, int severity, int id, String description) {
            try (Arena arena = Arena.ofConfined()) {
                int hr = (int) ADD_MESSAGE.invokeExact(this.fn(Vtbl.ID3D12InfoQueue.ADD_MESSAGE), this.ptr(),
                        category, severity, id, arena.allocateFrom(description, StandardCharsets.UTF_8));
                Hr.check(hr, "ID3D12InfoQueue::AddMessage");
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }
    }
}
