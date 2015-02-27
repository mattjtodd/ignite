/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.transactions;

import org.apache.ignite.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.distributed.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.marshaller.optimized.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import javax.cache.expiry.*;
import javax.cache.processor.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.internal.processors.cache.GridCacheOperation.*;

/**
 * Transaction entry. Note that it is essential that this class does not override
 * {@link #equals(Object)} method, as transaction entries should use referential
 * equality.
 */
public class IgniteTxEntry implements GridPeerDeployAware, Externalizable, OptimizedMarshallable {
    /** */
    private static final long serialVersionUID = 0L;

    /** */
    @SuppressWarnings({"NonConstantFieldWithUpperCaseName", "AbbreviationUsage", "UnusedDeclaration"})
    private static Object GG_CLASS_ID;

    /** Owning transaction. */
    @GridToStringExclude
    private IgniteInternalTx tx;

    /** Cache key. */
    @GridToStringInclude
    private KeyCacheObject key;

    /** Cache ID. */
    private int cacheId;

    /** Transient tx key. */
    private IgniteTxKey txKey;

    /** Cache value. */
    @GridToStringInclude
    private TxEntryValueHolder val = new TxEntryValueHolder();

    /** Visible value for peek. */
    @GridToStringInclude
    private TxEntryValueHolder prevVal = new TxEntryValueHolder();

    /** Filter bytes. */
    private byte[] filterBytes;

    /** Transform. */
    @GridToStringInclude
    private Collection<T2<EntryProcessor<Object, Object, Object>, Object[]>> entryProcessorsCol;

    /** Transform closure bytes. */
    @GridToStringExclude
    private byte[] transformClosBytes;

    /** Time to live. */
    private long ttl;

    /** DR expire time (explicit) */
    private long conflictExpireTime = CU.EXPIRE_TIME_CALCULATE;

    /** Conflict version. */
    private GridCacheVersion conflictVer;

    /** Explicit lock version if there is one. */
    @GridToStringInclude
    private GridCacheVersion explicitVer;

    /** DHT version. */
    private transient volatile GridCacheVersion dhtVer;

    /** Put filters. */
    @GridToStringInclude
    private IgnitePredicate<Cache.Entry<Object, Object>>[] filters;

    /** Flag indicating whether filters passed. Used for fast-commit transactions. */
    private boolean filtersPassed;

    /** Flag indicating that filter is set and can not be replaced. */
    private transient boolean filtersSet;

    /** Underlying cache entry. */
    private transient volatile GridCacheEntryEx entry;

    /** Cache registry. */
    private transient GridCacheContext<?, ?> ctx;

    /** Prepared flag to prevent multiple candidate add. */
    @SuppressWarnings({"TransientFieldNotInitialized"})
    private transient AtomicBoolean prepared = new AtomicBoolean();

    /** Lock flag for colocated cache. */
    private transient boolean locked;

    /** Assigned node ID (required only for partitioned cache). */
    private transient UUID nodeId;

    /** Flag if this node is a back up node. */
    private boolean locMapped;

    /** Group lock entry flag. */
    private boolean grpLock;

    /** Deployment enabled flag. */
    private boolean depEnabled;

    /** Expiry policy. */
    private ExpiryPolicy expiryPlc;

    /** Expiry policy transfer flag. */
    private boolean transferExpiryPlc;

    /**
     * Required by {@link Externalizable}
     */
    public IgniteTxEntry() {
        /* No-op. */
    }

    /**
     * This constructor is meant for remote transactions.
     *
     * @param ctx Cache registry.
     * @param tx Owning transaction.
     * @param op Operation.
     * @param val Value.
     * @param ttl Time to live.
     * @param conflictExpireTime DR expire time.
     * @param entry Cache entry.
     * @param conflictVer Data center replication version.
     */
    public IgniteTxEntry(GridCacheContext<?, ?> ctx,
        IgniteInternalTx tx,
        GridCacheOperation op,
        CacheObject val,
        long ttl,
        long conflictExpireTime,
        GridCacheEntryEx entry,
        @Nullable GridCacheVersion conflictVer) {
        assert ctx != null;
        assert tx != null;
        assert op != null;
        assert entry != null;

        this.ctx = ctx;
        this.tx = tx;
        this.val.value(op, val, false, false);
        this.entry = entry;
        this.ttl = ttl;
        this.conflictExpireTime = conflictExpireTime;
        this.conflictVer = conflictVer;

        key = entry.key();

        cacheId = entry.context().cacheId();

        depEnabled = ctx.gridDeploy().enabled();
    }

    /**
     * This constructor is meant for local transactions.
     *
     * @param ctx Cache registry.
     * @param tx Owning transaction.
     * @param op Operation.
     * @param val Value.
     * @param entryProcessor Entry processor.
     * @param invokeArgs Optional arguments for EntryProcessor.
     * @param ttl Time to live.
     * @param entry Cache entry.
     * @param filters Put filters.
     * @param conflictVer Data center replication version.
     */
    public IgniteTxEntry(GridCacheContext<?, ?> ctx,
        IgniteInternalTx tx,
        GridCacheOperation op,
        CacheObject val,
        EntryProcessor<Object, Object, Object> entryProcessor,
        Object[] invokeArgs,
        long ttl,
        GridCacheEntryEx entry,
        IgnitePredicate<Cache.Entry<Object, Object>>[] filters,
        GridCacheVersion conflictVer) {
        assert ctx != null;
        assert tx != null;
        assert op != null;
        assert entry != null;

        this.ctx = ctx;
        this.tx = tx;
        this.val.value(op, val, false, false);
        this.entry = entry;
        this.ttl = ttl;
        this.filters = filters;
        this.conflictVer = conflictVer;

        if (entryProcessor != null)
            addEntryProcessor(entryProcessor, invokeArgs);

        key = entry.key();

        cacheId = entry.context().cacheId();

        depEnabled = ctx.gridDeploy().enabled();
    }

    /**
     * @return Cache context for this tx entry.
     */
    public GridCacheContext<?, ?> context() {
        return ctx;
    }

    /**
     * @return Flag indicating if this entry is affinity mapped to the same node.
     */
    public boolean locallyMapped() {
        return locMapped;
    }

    /**
     * @param locMapped Flag indicating if this entry is affinity mapped to the same node.
     */
    public void locallyMapped(boolean locMapped) {
        this.locMapped = locMapped;
    }

    /**
     * @return {@code True} if this entry was added in group lock transaction and
     *      this is not a group lock entry.
     */
    public boolean groupLockEntry() {
        return grpLock;
    }

    /**
     * @param grpLock {@code True} if this entry was added in group lock transaction and
     *      this is not a group lock entry.
     */
    public void groupLockEntry(boolean grpLock) {
        this.grpLock = grpLock;
    }

    /**
     * @param ctx Context.
     * @return Clean copy of this entry.
     */
    public IgniteTxEntry cleanCopy(GridCacheContext<?, ?> ctx) {
        IgniteTxEntry cp = new IgniteTxEntry();

        cp.key = key;
        cp.cacheId = cacheId;
        cp.ctx = ctx;

        cp.val = new TxEntryValueHolder();

        cp.filters = filters;
        cp.val.value(val.op(), val.value(), val.hasWriteValue(), val.hasReadValue());
        cp.entryProcessorsCol = entryProcessorsCol;
        cp.ttl = ttl;
        cp.conflictExpireTime = conflictExpireTime;
        cp.explicitVer = explicitVer;
        cp.grpLock = grpLock;
        cp.depEnabled = depEnabled;
        cp.conflictVer = conflictVer;
        cp.expiryPlc = expiryPlc;

        return cp;
    }

    /**
     * @return Node ID.
     */
    public UUID nodeId() {
        return nodeId;
    }

    /**
     * @param nodeId Node ID.
     */
    public void nodeId(UUID nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * @return DHT version.
     */
    public GridCacheVersion dhtVersion() {
        return dhtVer;
    }

    /**
     * @param dhtVer DHT version.
     */
    public void dhtVersion(GridCacheVersion dhtVer) {
        this.dhtVer = dhtVer;
    }

    /**
     * @return {@code True} if tx entry was marked as locked.
     */
    public boolean locked() {
        return locked;
    }

    /**
     * Marks tx entry as locked.
     */
    public void markLocked() {
        locked = true;
    }

    /**
     * @param val Value to set.
     */
    void setAndMarkValid(CacheObject val) {
        setAndMarkValid(op(), val, this.val.hasWriteValue(), this.val.hasReadValue());
    }

    /**
     * @param op Operation.
     * @param val Value to set.
     */
    void setAndMarkValid(GridCacheOperation op, CacheObject val) {
        setAndMarkValid(op, val, this.val.hasWriteValue(), this.val.hasReadValue());
    }

    /**
     * @param op Operation.
     * @param val Value to set.
     * @param hasReadVal Has read value flag.
     * @param hasWriteVal Has write value flag.
     */
    void setAndMarkValid(GridCacheOperation op, CacheObject val, boolean hasWriteVal, boolean hasReadVal) {
        this.val.value(op, val, hasWriteVal, hasReadVal);

        markValid();
    }

    /**
     * Marks this entry as value-has-bean-read. Effectively, makes values enlisted to transaction visible
     * to further peek operations.
     */
    void markValid() {
        prevVal.value(val.op(), val.value(), val.hasWriteValue(), val.hasReadValue());
    }

    /**
     * Marks entry as prepared.
     *
     * @return True if entry was marked prepared by this call.
     */
    boolean markPrepared() {
        return prepared.compareAndSet(false, true);
    }

    /**
     * @return Entry key.
     */
    public KeyCacheObject key() {
        return key;
    }

    /**
     * @return Cache ID.
     */
    public int cacheId() {
        return cacheId;
    }

    /**
     * @return Tx key.
     */
    public IgniteTxKey txKey() {
        if (txKey == null)
            txKey = new IgniteTxKey(key, cacheId);

        return txKey;
    }

    /**
     * @return Underlying cache entry.
     */
    public GridCacheEntryEx cached() {
        return entry;
    }

    /**
     * @param entry Cache entry.
     * @param keyBytes Key bytes, possibly {@code null}.
     */
    public void cached(GridCacheEntryEx entry, @Nullable byte[] keyBytes) {
        assert entry != null;

        assert entry.context() == ctx : "Invalid entry assigned to tx entry [txEntry=" + this +
            ", entry=" + entry + ", ctxNear=" + ctx.isNear() + ", ctxDht=" + ctx.isDht() + ']';

        this.entry = entry;
    }

    /**
     * @return Entry value.
     */
    @Nullable public CacheObject value() {
        return val.value();
    }

    /**
     * @return {@code True} if has value explicitly set.
     */
    public boolean hasValue() {
        return val.hasValue();
    }

    /**
     * @return {@code True} if has write value set.
     */
    public boolean hasWriteValue() {
        return val.hasWriteValue();
    }

    /**
     * @return {@code True} if has read value set.
     */
    public boolean hasReadValue() {
        return val.hasReadValue();
    }

    /**
     * @return Value visible for peek.
     */
    @Nullable public CacheObject previousValue() {
        return prevVal.value();
    }

    /**
     * @return {@code True} if has previous value explicitly set.
     */
    boolean hasPreviousValue() {
        return prevVal.hasValue();
    }

    /**
     * @return Previous operation to revert entry in case of filter failure.
     */
    @Nullable public GridCacheOperation previousOperation() {
        return prevVal.op();
    }

    /**
     * @return Time to live.
     */
    public long ttl() {
        return ttl;
    }

    /**
     * @param ttl Time to live.
     */
    public void ttl(long ttl) {
        this.ttl = ttl;
    }

    /**
     * @return Conflict expire time.
     */
    public long conflictExpireTime() {
        return conflictExpireTime;
    }

    /**
     * @param conflictExpireTime Conflict expire time.
     */
    public void conflictExpireTime(long conflictExpireTime) {
        this.conflictExpireTime = conflictExpireTime;
    }

    /**
     * @param val Entry value.
     * @param writeVal Write value flag.
     * @param readVal Read value flag.
     */
    public void value(@Nullable CacheObject val, boolean writeVal, boolean readVal) {
        this.val.value(this.val.op(), val, writeVal, readVal);
    }

    /**
     * Sets read value if this tx entry does not have write value yet.
     *
     * @param val Read value to set.
     */
    public void readValue(@Nullable CacheObject val) {
        this.val.value(this.val.op(), val, false, true);
    }

    /**
     * @param entryProcessor Entry processor.
     * @param invokeArgs Optional arguments for EntryProcessor.
     */
    public void addEntryProcessor(EntryProcessor<Object, Object, Object> entryProcessor, Object[] invokeArgs) {
        if (entryProcessorsCol == null)
            entryProcessorsCol = new LinkedList<>();

        entryProcessorsCol.add(new T2<>(entryProcessor, invokeArgs));

        // Must clear transform closure bytes since collection has changed.
        transformClosBytes = null;

        val.op(TRANSFORM);
    }

    /**
     * @return Collection of entry processors.
     */
    public Collection<T2<EntryProcessor<Object, Object, Object>, Object[]>> entryProcessors() {
        return entryProcessorsCol;
    }

    /**
     * @param cacheVal Value.
     * @return New value.
     */
    @SuppressWarnings("unchecked")
    public CacheObject applyEntryProcessors(CacheObject cacheVal) {
        Object key = CU.value(this.key, ctx, false);
        Object val = CU.value(cacheVal, ctx, false);

        for (T2<EntryProcessor<Object, Object, Object>, Object[]> t : entryProcessors()) {
            try {
                CacheInvokeEntry<Object, Object> invokeEntry = new CacheInvokeEntry<>(ctx, key, val);

                EntryProcessor processor = t.get1();

                processor.process(invokeEntry, t.get2());

                val = invokeEntry.getValue();
            }
            catch (Exception ignore) {
                // No-op.
            }
        }

        return ctx.toCacheObject(val);
// TODO IGNITE-51
//        if (ctx.portableEnabled())
//            val = (V)ctx.marshalToPortable(val);
//
//        return val;
    }

    /**
     * @param cacheVal Value.
     * @return New value.
     */
    public <V> V applyEntryProcessors(V cacheVal) {
        Object val = cacheVal;
        Object key = CU.value(this.key, ctx, false);

        for (T2<EntryProcessor<Object, Object, Object>, Object[]> t : entryProcessors()) {
            try {
                CacheInvokeEntry<Object, Object> invokeEntry = new CacheInvokeEntry<>(ctx, key, val);

                EntryProcessor processor = t.get1();

                processor.process(invokeEntry, t.get2());

                val = invokeEntry.getValue();
            }
            catch (Exception ignore) {
                // No-op.
            }
        }

        return (V)val;
    }

    /**
     * @param entryProcessorsCol Collection of entry processors.
     */
    public void entryProcessors(
        @Nullable Collection<T2<EntryProcessor<Object, Object, Object>, Object[]>> entryProcessorsCol) {
        this.entryProcessorsCol = entryProcessorsCol;

        // Must clear transform closure bytes since collection has changed.
        transformClosBytes = null;
    }

    /**
     * @return Cache operation.
     */
    public GridCacheOperation op() {
        return val.op();
    }

    /**
     * @param op Cache operation.
     */
    public void op(GridCacheOperation op) {
        val.op(op);
    }

    /**
     * @return {@code True} if read entry.
     */
    public boolean isRead() {
        return op() == READ;
    }

    /**
     * @param explicitVer Explicit version.
     */
    public void explicitVersion(GridCacheVersion explicitVer) {
        this.explicitVer = explicitVer;
    }

    /**
     * @return Explicit version.
     */
    public GridCacheVersion explicitVersion() {
        return explicitVer;
    }

    /**
     * @return Conflict version.
     */
    @Nullable public GridCacheVersion conflictVersion() {
        return conflictVer;
    }

    /**
     * @param conflictVer Conflict version.
     */
    public void conflictVersion(@Nullable GridCacheVersion conflictVer) {
        this.conflictVer = conflictVer;
    }

    /**
     * @return Put filters.
     */
    public IgnitePredicate<Cache.Entry<Object, Object>>[] filters() {
        return filters;
    }

    /**
     * @param filters Put filters.
     */
    public void filters(IgnitePredicate<Cache.Entry<Object, Object>>[] filters) {
        filterBytes = null;

        this.filters = filters;
    }

    /**
     * @return {@code True} if filters passed for fast-commit transactions.
     */
    public boolean filtersPassed() {
        return filtersPassed;
    }

    /**
     * @param filtersPassed {@code True} if filters passed for fast-commit transactions.
     */
    public void filtersPassed(boolean filtersPassed) {
        this.filtersPassed = filtersPassed;
    }

    /**
     * @return {@code True} if filters are set.
     */
    public boolean filtersSet() {
        return filtersSet;
    }

    /**
     * @param filtersSet {@code True} if filters are set and should not be replaced.
     */
    public void filtersSet(boolean filtersSet) {
        this.filtersSet = filtersSet;
    }

    /**
     * @param ctx Context.
     * @param transferExpiry {@code True} if expire policy should be marshalled.
     * @throws IgniteCheckedException If failed.
     */
    public void marshal(GridCacheSharedContext<?, ?> ctx, boolean transferExpiry) throws IgniteCheckedException {
        // Do not serialize filters if they are null.
        if (depEnabled) {
            if (transformClosBytes == null && entryProcessorsCol != null)
                transformClosBytes = CU.marshal(ctx, entryProcessorsCol);

            if (F.isEmptyOrNulls(filters))
                filterBytes = null;
            else if (filterBytes == null)
                filterBytes = CU.marshal(ctx, filters);
        }

        if (transferExpiry)
            transferExpiryPlc = expiryPlc != null && expiryPlc != this.ctx.expiry();

        val.marshal(ctx, context(), depEnabled);
    }

    /**
     * Unmarshalls entry.
     *
     * @param ctx Cache context.
     * @param near Near flag.
     * @param clsLdr Class loader.
     * @throws IgniteCheckedException If un-marshalling failed.
     */
    public void unmarshal(GridCacheSharedContext<?, ?> ctx, boolean near, ClassLoader clsLdr) throws IgniteCheckedException {
// TODO IGNITE-51.
//        if (this.ctx == null) {
//            GridCacheContext<?, ?> cacheCtx = ctx.cacheContext(cacheId);
//
//            if (cacheCtx.isNear() && !near)
//                cacheCtx = cacheCtx.near().dht().context();
//            else if (!cacheCtx.isNear() && near)
//                cacheCtx = cacheCtx.dht().near().context();
//
//            this.ctx = cacheCtx;
//        }
//
//        if (depEnabled) {
//            // Don't unmarshal more than once by checking key for null.
//            if (key == null)
//                key = ctx.marshaller().unmarshal(keyBytes, clsLdr);
//
//            // Unmarshal transform closure anyway if it exists.
//            if (transformClosBytes != null && entryProcessorsCol == null)
//                entryProcessorsCol = ctx.marshaller().unmarshal(transformClosBytes, clsLdr);
//
//            if (filters == null && filterBytes != null) {
//                filters = ctx.marshaller().unmarshal(filterBytes, clsLdr);
//
//                if (filters == null)
//                    filters = CU.empty();
//            }
//        }
//
//        val.unmarshal(this.ctx, clsLdr, depEnabled);
    }

    /**
     * @param expiryPlc Expiry policy.
     */
    public void expiry(@Nullable ExpiryPolicy expiryPlc) {
        this.expiryPlc = expiryPlc;
    }

    /**
     * @return Expiry policy.
     */
    @Nullable public ExpiryPolicy expiry() {
        return expiryPlc;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
// TODO IGNITE-51.
//        out.writeBoolean(depEnabled);
//
//        if (depEnabled) {
//            U.writeByteArray(out, keyBytes);
//            U.writeByteArray(out, transformClosBytes);
//            U.writeByteArray(out, filterBytes);
//        }
//        else {
//            out.writeObject(key);
//            U.writeCollection(out, entryProcessorsCol);
//            U.writeArray(out, filters);
//        }
//
//        out.writeInt(cacheId);
//
//        val.writeTo(out);
//
//        out.writeLong(ttl);
//
//        CU.writeVersion(out, explicitVer);
//        out.writeBoolean(grpLock);
//
//        if (conflictExpireTime != CU.EXPIRE_TIME_CALCULATE) {
//            out.writeBoolean(true);
//            out.writeLong(conflictExpireTime);
//        }
//        else
//            out.writeBoolean(false);
//
//        CU.writeVersion(out, conflictVer);
//
//        out.writeObject(transferExpiryPlc ? new IgniteExternalizableExpiryPolicy(expiryPlc) : null);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
// TODO IGNITE-51.
//        depEnabled = in.readBoolean();
//
//        if (depEnabled) {
//            keyBytes = U.readByteArray(in);
//            transformClosBytes = U.readByteArray(in);
//            filterBytes = U.readByteArray(in);
//        }
//        else {
//            key = (K)in.readObject();
//            entryProcessorsCol = U.readCollection(in);
//            filters = GridCacheUtils.readEntryFilterArray(in);
//        }
//
//        cacheId = in.readInt();
//
//        val.readFrom(in);
//
//        ttl = in.readLong();
//
//        explicitVer = CU.readVersion(in);
//        grpLock = in.readBoolean();
//
//        conflictExpireTime = in.readBoolean() ? in.readLong() : CU.EXPIRE_TIME_CALCULATE;
//        conflictVer = CU.readVersion(in);
//
//        expiryPlc = (ExpiryPolicy)in.readObject();
    }

    /** {@inheritDoc} */
    @Override public Object ggClassId() {
        return GG_CLASS_ID;
    }

    /** {@inheritDoc} */
    @Override public Class<?> deployClass() {
        ClassLoader clsLdr = getClass().getClassLoader();

        CacheObject val = value();

        // First of all check classes that may be loaded by class loader other than application one.
        return key != null && !clsLdr.equals(key.getClass().getClassLoader()) ?
            key.getClass() : val != null ? val.getClass() : getClass();
    }

    /** {@inheritDoc} */
    @Override public ClassLoader classLoader() {
        return deployClass().getClassLoader();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return GridToStringBuilder.toString(IgniteTxEntry.class, this, "xidVer", tx == null ? "null" : tx.xidVersion());
    }

    /**
     * Auxiliary class to hold value, value-has-been-set flag, value update operation, value bytes.
     */
    private static class TxEntryValueHolder {
        /** */
        @GridToStringInclude
        private CacheObject val;

        /** */
        @GridToStringInclude
        private GridCacheOperation op = NOOP;

        /** Flag indicating that value has been set for write. */
        private boolean hasWriteVal;

        /** Flag indicating that value has been set for read. */
        private boolean hasReadVal;

        /** Flag indicating that bytes were sent. */
        private boolean valBytesSent;

        /**
         * @param op Cache operation.
         * @param val Value.
         * @param hasWriteVal Write value presence flag.
         * @param hasReadVal Read value presence flag.
         */
        public void value(GridCacheOperation op, CacheObject val, boolean hasWriteVal, boolean hasReadVal) {
            if (hasReadVal && this.hasWriteVal)
                return;

            this.op = op;
            this.val = val;

            this.hasWriteVal = hasWriteVal || op == CREATE || op == UPDATE || op == DELETE;
            this.hasReadVal = hasReadVal || op == READ;
        }

        /**
         * @return {@code True} if has read or write value.
         */
        public boolean hasValue() {
            return hasWriteVal || hasReadVal;
        }

        /**
         * Gets stored value.
         *
         * @return Value.
         */
        public CacheObject value() {
            return val;
        }

        /**
         * @param val Stored value.
         */
        public void value(@Nullable CacheObject val) {
            this.val = val;
        }

        /**
         * Gets cache operation.
         *
         * @return Cache operation.
         */
        public GridCacheOperation op() {
            return op;
        }

        /**
         * Sets cache operation.
         *
         * @param op Cache operation.
         */
        public void op(GridCacheOperation op) {
            this.op = op;
        }

        /**
         * @return {@code True} if write value was set.
         */
        public boolean hasWriteValue() {
            return hasWriteVal;
        }

        /**
         * @return {@code True} if read value was set.
         */
        public boolean hasReadValue() {
            return hasReadVal;
        }

        /**
         * @param sharedCtx Shared cache context.
         * @param ctx Cache context.
         * @param depEnabled Deployment enabled flag.
         * @throws IgniteCheckedException If marshaling failed.
         */
        public void marshal(GridCacheSharedContext<?, ?> sharedCtx, GridCacheContext<?, ?> ctx, boolean depEnabled)
            throws IgniteCheckedException {
// TODO IGNITE-51.
//            boolean valIsByteArr = val != null && val instanceof byte[];
//
//            // Do not send write values to remote nodes.
//            if (hasWriteVal && val != null && !valIsByteArr && valBytes == null &&
//                (depEnabled || !ctx.isUnmarshalValues()))
//                valBytes = CU.marshal(sharedCtx, val);
//
//            valBytesSent = hasWriteVal && !valIsByteArr && valBytes != null && (depEnabled || !ctx.isUnmarshalValues());
        }

        /**
         * @param ctx Cache context.
         * @param ldr Class loader.
         * @param depEnabled Deployment enabled flag.
         * @throws IgniteCheckedException If unmarshalling failed.
         */
        public void unmarshal(GridCacheContext<?, ?> ctx, ClassLoader ldr, boolean depEnabled) throws IgniteCheckedException {
// TODO IGNITE-51.
//            if (valBytes != null && val == null && (ctx.isUnmarshalValues() || op == TRANSFORM || depEnabled))
//                val = ctx.marshaller().unmarshal(valBytes, ldr);
        }

        /**
         * @param out Data output.
         * @throws IOException If failed.
         */
        public void writeTo(ObjectOutput out) throws IOException {
// TODO IGNITE-51.
//            out.writeBoolean(hasWriteVal);
//            out.writeBoolean(valBytesSent);
//
//            if (hasWriteVal) {
//                if (valBytesSent)
//                    U.writeByteArray(out, valBytes);
//                else {
//                    if (val != null && val instanceof byte[]) {
//                        out.writeBoolean(true);
//
//                        U.writeByteArray(out, (byte[]) val);
//                    }
//                    else {
//                        out.writeBoolean(false);
//
//                        out.writeObject(val);
//                    }
//                }
//            }
//
//            out.writeInt(op.ordinal());
        }

        /**
         * @param in Data input.
         * @throws IOException If failed.
         * @throws ClassNotFoundException If failed.
         */
        @SuppressWarnings("unchecked")
        public void readFrom(ObjectInput in) throws IOException, ClassNotFoundException {
// TODO IGNITE-51.
//            hasWriteVal = in.readBoolean();
//            valBytesSent = in.readBoolean();
//
//            if (hasWriteVal) {
//                if (valBytesSent)
//                    valBytes = U.readByteArray(in);
//                else
//                    val = in.readBoolean() ? (V) U.readByteArray(in) : (V)in.readObject();
//            }
//
//            op = fromOrdinal(in.readInt());
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "[op=" + op +", val=" + val + ']';
        }
    }
}
