/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.nashorn.internal.runtime.linker;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jdk.internal.dynalink.CallSiteDescriptor;
import jdk.internal.dynalink.support.AbstractCallSiteDescriptor;
import jdk.internal.dynalink.support.CallSiteDescriptorFactory;

/**
 * Nashorn-specific implementation of Dynalink's {@link CallSiteDescriptor}. The reason we have our own subclass is that
 * we can have a more compact representation, as we know that we're always only using {@code "dyn:*"} operations; also
 * we're storing flags in an additional primitive field.
 */
public final class NashornCallSiteDescriptor extends AbstractCallSiteDescriptor {
    /** Flags that the call site references a scope variable (it's an identifier reference or a var declaration, not a
     * property access expression. */
    public static final int CALLSITE_SCOPE                = 0x01;
    /** Flags that the call site is in code that uses ECMAScript strict mode. */
    public static final int CALLSITE_STRICT               = 0x02;
    /** Flags that a property getter or setter call site references a scope variable that is located at a known distance
     * in the scope chain. Such getters and setters can often be linked more optimally using these assumptions. */
    public static final int CALLSITE_FAST_SCOPE    = 0x400;

    /** Flags that the call site is profiled; Contexts that have {@code "profile.callsites"} boolean property set emit
     * code where call sites have this flag set. */
    public static final int CALLSITE_PROFILE          = 0x10;
    /** Flags that the call site is traced; Contexts that have {@code "trace.callsites"} property set emit code where
     * call sites have this flag set. */
    public static final int CALLSITE_TRACE            = 0x20;
    /** Flags that the call site linkage miss (and thus, relinking) is traced; Contexts that have the keyword
     * {@code "miss"} in their {@code "trace.callsites"} property emit code where call sites have this flag set. */
    public static final int CALLSITE_TRACE_MISSES     = 0x40;
    /** Flags that entry/exit to/from the method linked at call site are traced; Contexts that have the keyword
     * {@code "enterexit"} in their {@code "trace.callsites"} property emit code where call sites have this flag
     * set. */
    public static final int CALLSITE_TRACE_ENTEREXIT  = 0x80;
    /** Flags that values passed as arguments to and returned from the method linked at call site are traced; Contexts
     * that have the keyword {@code "values"} in their {@code "trace.callsites"} property emit code where call sites
     * have this flag set. */
    public static final int CALLSITE_TRACE_VALUES    = 0x100;
    /** Ordinarily, when {@link #CALLSITE_TRACE_VALUES} is set, scope objects are not printed in the trace but instead
     * the word {@code "SCOPE"} is printed instead With this flag, scope objects are also printed. Contexts that have
     * the keyword {@code "scope"} in their {@code "trace.callsites"} property emit code where call sites have this flag
     * set. */
    public static final int CALLSITE_TRACE_SCOPE      = 0x200;

    private static final ClassValue<ConcurrentMap<NashornCallSiteDescriptor, NashornCallSiteDescriptor>> canonicals =
            new ClassValue<ConcurrentMap<NashornCallSiteDescriptor,NashornCallSiteDescriptor>>() {
        @Override
        protected ConcurrentMap<NashornCallSiteDescriptor, NashornCallSiteDescriptor> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private final MethodHandles.Lookup lookup;
    private final String operator;
    private final String operand;
    private final MethodType methodType;
    private final int flags;

    /**
     * Retrieves a Nashorn call site descriptor with the specified values. Since call site descriptors are immutable
     * this method is at liberty to retrieve canonicalized instances (although it is not guaranteed it will do so).
     * @param lookup the lookup describing the script
     * @param name the name at the call site, e.g. {@code "dyn:getProp|getElem|getMethod:color"}.
     * @param methodType the method type at the call site
     * @param flags Nashorn-specific call site flags
     * @return a call site descriptor with the specified values.
     */
    public static NashornCallSiteDescriptor get(final MethodHandles.Lookup lookup, final String name,
            final MethodType methodType, final int flags) {
        final String[] tokenizedName = CallSiteDescriptorFactory.tokenizeName(name);
        assert tokenizedName.length == 2 || tokenizedName.length == 3;
        assert "dyn".equals(tokenizedName[0]);
        assert tokenizedName[1] != null;
        // TODO: see if we can move mangling/unmangling into Dynalink
        return get(lookup, tokenizedName[1], tokenizedName.length == 3 ? tokenizedName[2].intern() : null,
                methodType, flags);
    }

    private static NashornCallSiteDescriptor get(final MethodHandles.Lookup lookup, final String operator, final String operand, final MethodType methodType, final int flags) {
        final NashornCallSiteDescriptor csd = new NashornCallSiteDescriptor(lookup, operator, operand, methodType, flags);
        // Many of these call site descriptors are identical (e.g. every getter for a property color will be
        // "dyn:getProp:color(Object)Object", so it makes sense canonicalizing them.
        final ConcurrentMap<NashornCallSiteDescriptor, NashornCallSiteDescriptor> classCanonicals = canonicals.get(lookup.lookupClass());
        final NashornCallSiteDescriptor canonical = classCanonicals.putIfAbsent(csd, csd);
        return canonical != null ? canonical : csd;
    }

    private NashornCallSiteDescriptor(final MethodHandles.Lookup lookup, final String operator, final String operand,
            final MethodType methodType, final int flags) {
        this.lookup = lookup;
        this.operator = operator;
        this.operand = operand;
        this.methodType = methodType;
        this.flags = flags;
    }

    @Override
    public int getNameTokenCount() {
        return operand == null ? 2 : 3;
    }

    @Override
    public String getNameToken(final int i) {
        switch(i) {
        case 0: return "dyn";
        case 1: return operator;
        case 2:
            if(operand != null) {
                return operand;
            }
            break;
        default:
            break;
        }
        throw new IndexOutOfBoundsException(String.valueOf(i));
    }

    @Override
    public Lookup getLookup() {
        return lookup;
    }

    @Override
    public boolean equals(final CallSiteDescriptor csd) {
        return super.equals(csd) && flags == getFlags(csd);
    }

    @Override
    public MethodType getMethodType() {
        return methodType;
    }

    /**
     * Returns the operator (e.g. {@code "getProp"}) in this call site descriptor's name. Equivalent to
     * {@code getNameToken(CallSiteDescriptor.OPERATOR)}. The returned operator can be composite.
     * @return the operator in this call site descriptor's name.
     */
    public String getOperator() {
        return operator;
    }

    /**
     * Returns the first operator in this call site descriptor's name. E.g. if this call site descriptor has a composite
     * operation {@code "getProp|getMethod|getElem"}, it will return {@code "getProp"}. Nashorn - being a ECMAScript
     * engine - does not distinguish between property, element, and method namespace; ECMAScript objects just have one
     * single property namespace for all these, therefore it is largely irrelevant what the composite operation is
     * structured like; if the first operation can't be satisfied, neither can the others. The first operation is
     * however sometimes used to slightly alter the semantics; for example, a distinction between {@code "getProp"} and
     * {@code "getMethod"} being the first operation can translate into whether {@code "__noSuchProperty__"} or
     * {@code "__noSuchMethod__"} will be executed in case the property is not found.
     * @return the first operator in this call site descriptor's name.
     */
    public String getFirstOperator() {
        final int delim = operator.indexOf(CallSiteDescriptor.OPERATOR_DELIMITER);
        return delim == -1 ? operator : operator.substring(0, delim);
    }

    /**
     * Returns the named operand in this descriptor's name. Equivalent to
     * {@code getNameToken(CallSiteDescriptor.NAME_OPERAND)}. E.g. for operation {@code "dyn:getProp:color"}, returns
     * {@code "color"}. For call sites without named operands (e.g. {@code "dyn:new"}) returns null.
     * @return the named operand in this descriptor's name.
     */
    public String getOperand() {
        return operand;
    }

    /**
     * Returns the Nashorn-specific flags for this call site descriptor.
     * @param desc the descriptor. It can be any kind of a call site descriptor, not necessarily a
     * {@code NashornCallSiteDescriptor}. This allows for graceful interoperability when linking Nashorn with code
     * generated outside of Nashorn.
     * @return the Nashorn-specific flags for the call site, or 0 if the passed descriptor is not a Nashorn call site
     * descriptor.
     */
    private static int getFlags(final CallSiteDescriptor desc) {
        return desc instanceof NashornCallSiteDescriptor ? ((NashornCallSiteDescriptor)desc).flags : 0;
    }

    /**
     * Returns true if this descriptor has the specified flag set, see {@code CALLSITE_*} constants in this class.
     * @param flag the tested flag
     * @return true if the flag is set, false otherwise
     */
    private boolean isFlag(final int flag) {
        return (flags & flag) != 0;
    }

    /**
     * Returns true if this descriptor has the specified flag set, see {@code CALLSITE_*} constants in this class.
     * @param desc the descriptor. It can be any kind of a call site descriptor, not necessarily a
     * {@code NashornCallSiteDescriptor}. This allows for graceful interoperability when linking Nashorn with code
     * generated outside of Nashorn.
     * @param flag the tested flag
     * @return true if the flag is set, false otherwise (it will be false if the decriptor is not a Nashorn call site
     * descriptor).
     */
    private static boolean isFlag(final CallSiteDescriptor desc, final int flag) {
        return (getFlags(desc) & flag) != 0;
    }

    /**
     * Returns true if this descriptor is a Nashorn call site descriptor and has the {@link  #CALLSITE_SCOPE} flag set.
     * @param desc the descriptor. It can be any kind of a call site descriptor, not necessarily a
     * {@code NashornCallSiteDescriptor}. This allows for graceful interoperability when linking Nashorn with code
     * generated outside of Nashorn.
     * @return true if the descriptor is a Nashorn call site descriptor, and the flag is set, false otherwise.
     */
    public static boolean isScope(final CallSiteDescriptor desc) {
        return isFlag(desc, CALLSITE_SCOPE);
    }

    /**
     * Returns true if this descriptor is a Nashorn call site descriptor and has the {@link  #CALLSITE_FAST_SCOPE} flag set.
     * @param desc the descriptor. It can be any kind of a call site descriptor, not necessarily a
     * {@code NashornCallSiteDescriptor}. This allows for graceful interoperability when linking Nashorn with code
     * generated outside of Nashorn.
     * @return true if the descriptor is a Nashorn call site descriptor, and the flag is set, false otherwise.
     */
    public static boolean isFastScope(final CallSiteDescriptor desc) {
        return isFlag(desc, CALLSITE_FAST_SCOPE);
    }

    /**
     * Returns true if this descriptor is a Nashorn call site descriptor and has the {@link  #CALLSITE_STRICT} flag set.
     * @param desc the descriptor. It can be any kind of a call site descriptor, not necessarily a
     * {@code NashornCallSiteDescriptor}. This allows for graceful interoperability when linking Nashorn with code
     * generated outside of Nashorn.
     * @return true if the descriptor is a Nashorn call site descriptor, and the flag is set, false otherwise.
     */
    public static boolean isStrict(final CallSiteDescriptor desc) {
        return isFlag(desc, CALLSITE_STRICT);
    }

    boolean isProfile() {
        return isFlag(CALLSITE_PROFILE);
    }

    boolean isTrace() {
        return isFlag(CALLSITE_TRACE);
    }

    boolean isTraceMisses() {
        return isFlag(CALLSITE_TRACE_MISSES);
    }

    boolean isTraceEnterExit() {
        return isFlag(CALLSITE_TRACE_ENTEREXIT);
    }

    boolean isTraceObjects() {
        return isFlag(CALLSITE_TRACE_VALUES);
    }

    boolean isTraceScope() {
        return isFlag(CALLSITE_TRACE_SCOPE);
    }

    @Override
    public CallSiteDescriptor changeMethodType(final MethodType newMethodType) {
        return get(getLookup(), operator, operand, newMethodType, flags);
    }
}
