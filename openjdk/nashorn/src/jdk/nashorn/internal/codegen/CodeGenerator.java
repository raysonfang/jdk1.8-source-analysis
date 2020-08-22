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

package jdk.nashorn.internal.codegen;

import static jdk.nashorn.internal.codegen.ClassEmitter.Flag.PRIVATE;
import static jdk.nashorn.internal.codegen.ClassEmitter.Flag.STATIC;
import static jdk.nashorn.internal.codegen.CompilerConstants.ARGUMENTS;
import static jdk.nashorn.internal.codegen.CompilerConstants.CALLEE;
import static jdk.nashorn.internal.codegen.CompilerConstants.GET_MAP;
import static jdk.nashorn.internal.codegen.CompilerConstants.GET_STRING;
import static jdk.nashorn.internal.codegen.CompilerConstants.QUICK_PREFIX;
import static jdk.nashorn.internal.codegen.CompilerConstants.REGEX_PREFIX;
import static jdk.nashorn.internal.codegen.CompilerConstants.RETURN;
import static jdk.nashorn.internal.codegen.CompilerConstants.SCOPE;
import static jdk.nashorn.internal.codegen.CompilerConstants.SPLIT_ARRAY_ARG;
import static jdk.nashorn.internal.codegen.CompilerConstants.SPLIT_PREFIX;
import static jdk.nashorn.internal.codegen.CompilerConstants.THIS;
import static jdk.nashorn.internal.codegen.CompilerConstants.VARARGS;
import static jdk.nashorn.internal.codegen.CompilerConstants.constructorNoLookup;
import static jdk.nashorn.internal.codegen.CompilerConstants.interfaceCallNoLookup;
import static jdk.nashorn.internal.codegen.CompilerConstants.methodDescriptor;
import static jdk.nashorn.internal.codegen.CompilerConstants.staticCallNoLookup;
import static jdk.nashorn.internal.codegen.CompilerConstants.typeDescriptor;
import static jdk.nashorn.internal.codegen.CompilerConstants.virtualCallNoLookup;
import static jdk.nashorn.internal.ir.Symbol.IS_INTERNAL;
import static jdk.nashorn.internal.ir.Symbol.IS_TEMP;
import static jdk.nashorn.internal.runtime.linker.NashornCallSiteDescriptor.CALLSITE_FAST_SCOPE;
import static jdk.nashorn.internal.runtime.linker.NashornCallSiteDescriptor.CALLSITE_SCOPE;
import static jdk.nashorn.internal.runtime.linker.NashornCallSiteDescriptor.CALLSITE_STRICT;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import jdk.nashorn.internal.codegen.ClassEmitter.Flag;
import jdk.nashorn.internal.codegen.CompilerConstants.Call;
import jdk.nashorn.internal.codegen.RuntimeCallSite.SpecializedRuntimeNode;
import jdk.nashorn.internal.codegen.types.ArrayType;
import jdk.nashorn.internal.codegen.types.Type;
import jdk.nashorn.internal.ir.AccessNode;
import jdk.nashorn.internal.ir.BaseNode;
import jdk.nashorn.internal.ir.BinaryNode;
import jdk.nashorn.internal.ir.Block;
import jdk.nashorn.internal.ir.BlockStatement;
import jdk.nashorn.internal.ir.BreakNode;
import jdk.nashorn.internal.ir.BreakableNode;
import jdk.nashorn.internal.ir.CallNode;
import jdk.nashorn.internal.ir.CaseNode;
import jdk.nashorn.internal.ir.CatchNode;
import jdk.nashorn.internal.ir.ContinueNode;
import jdk.nashorn.internal.ir.EmptyNode;
import jdk.nashorn.internal.ir.Expression;
import jdk.nashorn.internal.ir.ExpressionStatement;
import jdk.nashorn.internal.ir.ForNode;
import jdk.nashorn.internal.ir.FunctionNode;
import jdk.nashorn.internal.ir.FunctionNode.CompilationState;
import jdk.nashorn.internal.ir.IdentNode;
import jdk.nashorn.internal.ir.IfNode;
import jdk.nashorn.internal.ir.IndexNode;
import jdk.nashorn.internal.ir.LexicalContext;
import jdk.nashorn.internal.ir.LexicalContextNode;
import jdk.nashorn.internal.ir.LiteralNode;
import jdk.nashorn.internal.ir.LiteralNode.ArrayLiteralNode;
import jdk.nashorn.internal.ir.LiteralNode.ArrayLiteralNode.ArrayUnit;
import jdk.nashorn.internal.ir.LoopNode;
import jdk.nashorn.internal.ir.Node;
import jdk.nashorn.internal.ir.ObjectNode;
import jdk.nashorn.internal.ir.PropertyNode;
import jdk.nashorn.internal.ir.ReturnNode;
import jdk.nashorn.internal.ir.RuntimeNode;
import jdk.nashorn.internal.ir.RuntimeNode.Request;
import jdk.nashorn.internal.ir.SplitNode;
import jdk.nashorn.internal.ir.Statement;
import jdk.nashorn.internal.ir.SwitchNode;
import jdk.nashorn.internal.ir.Symbol;
import jdk.nashorn.internal.ir.TernaryNode;
import jdk.nashorn.internal.ir.ThrowNode;
import jdk.nashorn.internal.ir.TryNode;
import jdk.nashorn.internal.ir.UnaryNode;
import jdk.nashorn.internal.ir.VarNode;
import jdk.nashorn.internal.ir.WhileNode;
import jdk.nashorn.internal.ir.WithNode;
import jdk.nashorn.internal.ir.visitor.NodeOperatorVisitor;
import jdk.nashorn.internal.ir.visitor.NodeVisitor;
import jdk.nashorn.internal.objects.Global;
import jdk.nashorn.internal.objects.ScriptFunctionImpl;
import jdk.nashorn.internal.parser.Lexer.RegexToken;
import jdk.nashorn.internal.parser.TokenType;
import jdk.nashorn.internal.runtime.Context;
import jdk.nashorn.internal.runtime.Debug;
import jdk.nashorn.internal.runtime.DebugLogger;
import jdk.nashorn.internal.runtime.ECMAException;
import jdk.nashorn.internal.runtime.JSType;
import jdk.nashorn.internal.runtime.Property;
import jdk.nashorn.internal.runtime.PropertyMap;
import jdk.nashorn.internal.runtime.RecompilableScriptFunctionData;
import jdk.nashorn.internal.runtime.Scope;
import jdk.nashorn.internal.runtime.ScriptFunction;
import jdk.nashorn.internal.runtime.ScriptObject;
import jdk.nashorn.internal.runtime.ScriptRuntime;
import jdk.nashorn.internal.runtime.Source;
import jdk.nashorn.internal.runtime.Undefined;
import jdk.nashorn.internal.runtime.arrays.ArrayData;
import jdk.nashorn.internal.runtime.linker.LinkerCallSite;

/**
 * This is the lowest tier of the code generator. It takes lowered ASTs emitted
 * from Lower and emits Java byte code. The byte code emission logic is broken
 * out into MethodEmitter. MethodEmitter works internally with a type stack, and
 * keeps track of the contents of the byte code stack. This way we avoid a large
 * number of special cases on the form
 * <pre>
 * if (type == INT) {
 *     visitInsn(ILOAD, slot);
 * } else if (type == DOUBLE) {
 *     visitInsn(DOUBLE, slot);
 * }
 * </pre>
 * This quickly became apparent when the code generator was generalized to work
 * with all types, and not just numbers or objects.
 * <p>
 * The CodeGenerator visits nodes only once, tags them as resolved and emits
 * bytecode for them.
 */
final class CodeGenerator extends NodeOperatorVisitor<CodeGeneratorLexicalContext> {

    private static final String GLOBAL_OBJECT = Type.getInternalName(Global.class);

    private static final String SCRIPTFUNCTION_IMPL_OBJECT = Type.getInternalName(ScriptFunctionImpl.class);

    /** Constant data & installation. The only reason the compiler keeps this is because it is assigned
     *  by reflection in class installation */
    private final Compiler compiler;

    /** Call site flags given to the code generator to be used for all generated call sites */
    private final int callSiteFlags;

    /** How many regexp fields have been emitted */
    private int regexFieldCount;

    /** Line number for last statement. If we encounter a new line number, line number bytecode information
     *  needs to be generated */
    private int lastLineNumber = -1;

    /** When should we stop caching regexp expressions in fields to limit bytecode size? */
    private static final int MAX_REGEX_FIELDS = 2 * 1024;

    /** Current method emitter */
    private MethodEmitter method;

    /** Current compile unit */
    private CompileUnit unit;

    private static final DebugLogger LOG   = new DebugLogger("codegen", "nashorn.codegen.debug");

    /** From what size should we use spill instead of fields for JavaScript objects? */
    private static final int OBJECT_SPILL_THRESHOLD = 300;

    private final Set<String> emittedMethods = new HashSet<>();

    /**
     * Constructor.
     *
     * @param compiler
     */
    CodeGenerator(final Compiler compiler) {
        super(new CodeGeneratorLexicalContext());
        this.compiler      = compiler;
        this.callSiteFlags = compiler.getEnv()._callsite_flags;
    }

    /**
     * Gets the call site flags, adding the strict flag if the current function
     * being generated is in strict mode
     *
     * @return the correct flags for a call site in the current function
     */
    int getCallSiteFlags() {
        return lc.getCurrentFunction().isStrict() ? callSiteFlags | CALLSITE_STRICT : callSiteFlags;
    }

    /**
     * Load an identity node
     *
     * @param identNode an identity node to load
     * @return the method generator used
     */
    private MethodEmitter loadIdent(final IdentNode identNode, final Type type) {
        final Symbol symbol = identNode.getSymbol();

        if (!symbol.isScope()) {
            assert symbol.hasSlot() || symbol.isParam();
            return method.load(symbol).convert(type);
        }

        final String name   = symbol.getName();
        final Source source = lc.getCurrentFunction().getSource();

        if (CompilerConstants.__FILE__.name().equals(name)) {
            return method.load(source.getName());
        } else if (CompilerConstants.__DIR__.name().equals(name)) {
            return method.load(source.getBase());
        } else if (CompilerConstants.__LINE__.name().equals(name)) {
            return method.load(source.getLine(identNode.position())).convert(Type.OBJECT);
        } else {
            assert identNode.getSymbol().isScope() : identNode + " is not in scope!";

            final int flags = CALLSITE_SCOPE | getCallSiteFlags();
            method.loadCompilerConstant(SCOPE);

            if (isFastScope(symbol)) {
                // Only generate shared scope getter for fast-scope symbols so we know we can dial in correct scope.
                if (symbol.getUseCount() > SharedScopeCall.FAST_SCOPE_GET_THRESHOLD) {
                    return loadSharedScopeVar(type, symbol, flags);
                }
                return loadFastScopeVar(type, symbol, flags, identNode.isFunction());
            }
            return method.dynamicGet(type, identNode.getName(), flags, identNode.isFunction());
        }
    }

    /**
     * Check if this symbol can be accessed directly with a putfield or getfield or dynamic load
     *
     * @param symbol symbol to check for fast scope
     * @return true if fast scope
     */
    private boolean isFastScope(final Symbol symbol) {
        if (!symbol.isScope()) {
            return false;
        }

        if (!lc.inDynamicScope()) {
            // If there's no with or eval in context, and the symbol is marked as scoped, it is fast scoped. Such a
            // symbol must either be global, or its defining block must need scope.
            assert symbol.isGlobal() || lc.getDefiningBlock(symbol).needsScope() : symbol.getName();
            return true;
        }

        if (symbol.isGlobal()) {
            // Shortcut: if there's a with or eval in context, globals can't be fast scoped
            return false;
        }

        // Otherwise, check if there's a dynamic scope between use of the symbol and its definition
        final String name = symbol.getName();
        boolean previousWasBlock = false;
        for (final Iterator<LexicalContextNode> it = lc.getAllNodes(); it.hasNext();) {
            final LexicalContextNode node = it.next();
            if (node instanceof Block) {
                // If this block defines the symbol, then we can fast scope the symbol.
                final Block block = (Block)node;
                if (block.getExistingSymbol(name) == symbol) {
                    assert block.needsScope();
                    return true;
                }
                previousWasBlock = true;
            } else {
                if ((node instanceof WithNode && previousWasBlock) || (node instanceof FunctionNode && CodeGeneratorLexicalContext.isFunctionDynamicScope((FunctionNode)node))) {
                    // If we hit a scope that can have symbols introduced into it at run time before finding the defining
                    // block, the symbol can't be fast scoped. A WithNode only counts if we've immediately seen a block
                    // before - its block. Otherwise, we are currently processing the WithNode's expression, and that's
                    // obviously not subjected to introducing new symbols.
                    return false;
                }
                previousWasBlock = false;
            }
        }
        // Should've found the symbol defined in a block
        throw new AssertionError();
    }

    private MethodEmitter loadSharedScopeVar(final Type valueType, final Symbol symbol, final int flags) {
        method.load(isFastScope(symbol) ? getScopeProtoDepth(lc.getCurrentBlock(), symbol) : -1);
        final SharedScopeCall scopeCall = lc.getScopeGet(unit, valueType, symbol, flags | CALLSITE_FAST_SCOPE);
        return scopeCall.generateInvoke(method);
    }

    private MethodEmitter loadFastScopeVar(final Type valueType, final Symbol symbol, final int flags, final boolean isMethod) {
        loadFastScopeProto(symbol, false);
        return method.dynamicGet(valueType, symbol.getName(), flags | CALLSITE_FAST_SCOPE, isMethod);
    }

    private MethodEmitter storeFastScopeVar(final Symbol symbol, final int flags) {
        loadFastScopeProto(symbol, true);
        method.dynamicSet(symbol.getName(), flags | CALLSITE_FAST_SCOPE);
        return method;
    }

    private int getScopeProtoDepth(final Block startingBlock, final Symbol symbol) {
        int depth = 0;
        final String name = symbol.getName();
        for(final Iterator<Block> blocks = lc.getBlocks(startingBlock); blocks.hasNext();) {
            final Block currentBlock = blocks.next();
            if (currentBlock.getExistingSymbol(name) == symbol) {
                return depth;
            }
            if (currentBlock.needsScope()) {
                ++depth;
            }
        }
        return -1;
    }

    private void loadFastScopeProto(final Symbol symbol, final boolean swap) {
        final int depth = getScopeProtoDepth(lc.getCurrentBlock(), symbol);
        assert depth != -1;
        if (depth > 0) {
            if (swap) {
                method.swap();
            }
            for (int i = 0; i < depth; i++) {
                method.invoke(ScriptObject.GET_PROTO);
            }
            if (swap) {
                method.swap();
            }
        }
    }

    /**
     * Generate code that loads this node to the stack. This method is only
     * public to be accessible from the maps sub package. Do not call externally
     *
     * @param node node to load
     *
     * @return the method emitter used
     */
    MethodEmitter load(final Expression node) {
        return load(node, node.hasType() ? node.getType() : null, false);
    }

    // Test whether conversion from source to target involves a call of ES 9.1 ToPrimitive
    // with possible side effects from calling an object's toString or valueOf methods.
    private boolean noToPrimitiveConversion(final Type source, final Type target) {
        // Object to boolean conversion does not cause ToPrimitive call
        return source.isJSPrimitive() || !target.isJSPrimitive() || target.isBoolean();
    }

    MethodEmitter loadBinaryOperands(final Expression lhs, final Expression rhs, final Type type) {
        return loadBinaryOperands(lhs, rhs, type, false);
    }

    private MethodEmitter loadBinaryOperands(final Expression lhs, final Expression rhs, final Type type, final boolean baseAlreadyOnStack) {
        // ECMAScript 5.1 specification (sections 11.5-11.11 and 11.13) prescribes that when evaluating a binary
        // expression "LEFT op RIGHT", the order of operations must be: LOAD LEFT, LOAD RIGHT, CONVERT LEFT, CONVERT
        // RIGHT, EXECUTE OP. Unfortunately, doing it in this order defeats potential optimizations that arise when we
        // can combine a LOAD with a CONVERT operation (e.g. use a dynamic getter with the conversion target type as its
        // return value). What we do here is reorder LOAD RIGHT and CONVERT LEFT when possible; it is possible only when
        // we can prove that executing CONVERT LEFT can't have a side effect that changes the value of LOAD RIGHT.
        // Basically, if we know that either LEFT already is a primitive value, or does not have to be converted to
        // a primitive value, or RIGHT is an expression that loads without side effects, then we can do the
        // reordering and collapse LOAD/CONVERT into a single operation; otherwise we need to do the more costly
        // separate operations to preserve specification semantics.
        if (noToPrimitiveConversion(lhs.getType(), type) || rhs.isLocal()) {
            // Can reorder. Combine load and convert into single operations.
            load(lhs, type, baseAlreadyOnStack);
            load(rhs, type, false);
        } else {
            // Can't reorder. Load and convert separately.
            load(lhs, lhs.getType(), baseAlreadyOnStack);
            load(rhs, rhs.getType(), false);
            method.swap().convert(type).swap().convert(type);
        }

        return method;
    }

    MethodEmitter loadBinaryOperands(final BinaryNode node) {
        return loadBinaryOperands(node.lhs(), node.rhs(), node.getType(), false);
    }

    MethodEmitter load(final Expression node, final Type type) {
        return load(node, type, false);
    }

    private MethodEmitter load(final Expression node, final Type type, final boolean baseAlreadyOnStack) {
        final Symbol symbol = node.getSymbol();

        // If we lack symbols, we just generate what we see.
        if (symbol == null || type == null) {
            node.accept(this);
            return method;
        }

        assert !type.isUnknown();

        /*
         * The load may be of type IdentNode, e.g. "x", AccessNode, e.g. "x.y"
         * or IndexNode e.g. "x[y]". Both AccessNodes and IndexNodes are
         * BaseNodes and the logic for loading the base object is reused
         */
        final CodeGenerator codegen = this;

        node.accept(new NodeVisitor<LexicalContext>(lc) {
            @Override
            public boolean enterIdentNode(final IdentNode identNode) {
                loadIdent(identNode, type);
                return false;
            }

            @Override
            public boolean enterAccessNode(final AccessNode accessNode) {
                if (!baseAlreadyOnStack) {
                    load(accessNode.getBase(), Type.OBJECT);
                }
                assert method.peekType().isObject();
                method.dynamicGet(type, accessNode.getProperty().getName(), getCallSiteFlags(), accessNode.isFunction());
                return false;
            }

            @Override
            public boolean enterIndexNode(final IndexNode indexNode) {
                if (!baseAlreadyOnStack) {
                    load(indexNode.getBase(), Type.OBJECT);
                    load(indexNode.getIndex());
                }
                method.dynamicGetIndex(type, getCallSiteFlags(), indexNode.isFunction());
                return false;
            }

            @Override
            public boolean enterFunctionNode(FunctionNode functionNode) {
                // function nodes will always leave a constructed function object on stack, no need to load the symbol
                // separately as in enterDefault()
                lc.pop(functionNode);
                functionNode.accept(codegen);
                // NOTE: functionNode.accept() will produce a different FunctionNode that we discard. This incidentally
                // doesn't cause problems as we're never touching FunctionNode again after it's visited here - codegen
                // is the last element in the compilation pipeline, the AST it produces is not used externally. So, we
                // re-push the original functionNode.
                lc.push(functionNode);
                method.convert(type);
                return false;
            }

            @Override
            public boolean enterCallNode(CallNode callNode) {
                return codegen.enterCallNode(callNode, type);
            }

            @Override
            public boolean enterLiteralNode(LiteralNode<?> literalNode) {
                return codegen.enterLiteralNode(literalNode, type);
            }

            @Override
            public boolean enterDefault(final Node otherNode) {
                final Node currentDiscard = codegen.lc.getCurrentDiscard();
                otherNode.accept(codegen); // generate code for whatever we are looking at.
                if(currentDiscard != otherNode) {
                    method.load(symbol); // load the final symbol to the stack (or nop if no slot, then result is already there)
                    assert method.peekType() != null;
                    method.convert(type);
                }
                return false;
            }
        });

        return method;
    }

    @Override
    public boolean enterAccessNode(final AccessNode accessNode) {
        load(accessNode);
        return false;
    }

    /**
     * Initialize a specific set of vars to undefined. This has to be done at
     * the start of each method for local variables that aren't passed as
     * parameters.
     *
     * @param symbols list of symbols.
     */
    private void initSymbols(final Iterable<Symbol> symbols) {
        final LinkedList<Symbol> numbers = new LinkedList<>();
        final LinkedList<Symbol> objects = new LinkedList<>();

        for (final Symbol symbol : symbols) {
            /*
             * The following symbols are guaranteed to be defined and thus safe
             * from having undefined written to them: parameters internals this
             *
             * Otherwise we must, unless we perform control/escape analysis,
             * assign them undefined.
             */
            final boolean isInternal = symbol.isParam() || symbol.isInternal() || symbol.isThis() || !symbol.canBeUndefined();

            if (symbol.hasSlot() && !isInternal) {
                assert symbol.getSymbolType().isNumber() || symbol.getSymbolType().isObject() : "no potentially undefined narrower local vars than doubles are allowed: " + symbol + " in " + lc.getCurrentFunction();
                if (symbol.getSymbolType().isNumber()) {
                    numbers.add(symbol);
                } else if (symbol.getSymbolType().isObject()) {
                    objects.add(symbol);
                }
            }
        }

        initSymbols(numbers, Type.NUMBER);
        initSymbols(objects, Type.OBJECT);
    }

    private void initSymbols(final LinkedList<Symbol> symbols, final Type type) {
        final Iterator<Symbol> it = symbols.iterator();
        if(it.hasNext()) {
            method.loadUndefined(type);
            boolean hasNext;
            do {
                final Symbol symbol = it.next();
                hasNext = it.hasNext();
                if(hasNext) {
                    method.dup();
                }
                method.store(symbol);
            } while(hasNext);
        }
    }

    /**
     * Create symbol debug information.
     *
     * @param block block containing symbols.
     */
    private void symbolInfo(final Block block) {
        for (final Symbol symbol : block.getSymbols()) {
            if (symbol.hasSlot()) {
                method.localVariable(symbol, block.getEntryLabel(), block.getBreakLabel());
            }
        }
    }

    @Override
    public boolean enterBlock(final Block block) {
        if(lc.isFunctionBody() && emittedMethods.contains(lc.getCurrentFunction().getName())) {
            return false;
        }
        method.label(block.getEntryLabel());
        initLocals(block);

        return true;
    }

    @Override
    public Node leaveBlock(final Block block) {
        method.label(block.getBreakLabel());
        symbolInfo(block);

        if (block.needsScope() && !block.isTerminal()) {
            popBlockScope(block);
        }
        return block;
    }

    private void popBlockScope(final Block block) {
        final Label exitLabel     = new Label("block_exit");
        final Label recoveryLabel = new Label("block_catch");
        final Label skipLabel     = new Label("skip_catch");

        /* pop scope a la try-finally */
        method.loadCompilerConstant(SCOPE);
        method.invoke(ScriptObject.GET_PROTO);
        method.storeCompilerConstant(SCOPE);
        method._goto(skipLabel);
        method.label(exitLabel);

        method._catch(recoveryLabel);
        method.loadCompilerConstant(SCOPE);
        method.invoke(ScriptObject.GET_PROTO);
        method.storeCompilerConstant(SCOPE);
        method.athrow();
        method.label(skipLabel);
        method._try(block.getEntryLabel(), exitLabel, recoveryLabel, Throwable.class);
    }

    @Override
    public boolean enterBreakNode(final BreakNode breakNode) {
        lineNumber(breakNode);

        final BreakableNode breakFrom = lc.getBreakable(breakNode.getLabel());
        for (int i = 0; i < lc.getScopeNestingLevelTo(breakFrom); i++) {
            closeWith();
        }
        method.splitAwareGoto(lc, breakFrom.getBreakLabel());

        return false;
    }

    private int loadArgs(final List<Expression> args) {
        return loadArgs(args, null, false, args.size());
    }

    private int loadArgs(final List<Expression> args, final String signature, final boolean isVarArg, final int argCount) {
        // arg have already been converted to objects here.
        if (isVarArg || argCount > LinkerCallSite.ARGLIMIT) {
            loadArgsArray(args);
            return 1;
        }

        // pad with undefined if size is too short. argCount is the real number of args
        int n = 0;
        final Type[] params = signature == null ? null : Type.getMethodArguments(signature);
        for (final Expression arg : args) {
            assert arg != null;
            if (n >= argCount) {
                load(arg);
                method.pop(); // we had to load the arg for its side effects
            } else if (params != null) {
                load(arg, params[n]);
            } else {
                load(arg);
            }
            n++;
        }

        while (n < argCount) {
            method.loadUndefined(Type.OBJECT);
            n++;
        }

        return argCount;
    }


    @Override
    public boolean enterCallNode(final CallNode callNode) {
        return enterCallNode(callNode, callNode.getType());
    }

    private boolean enterCallNode(final CallNode callNode, final Type callNodeType) {
        lineNumber(callNode.getLineNumber());

        final List<Expression> args = callNode.getArgs();
        final Expression function = callNode.getFunction();
        final Block currentBlock = lc.getCurrentBlock();
        final CodeGeneratorLexicalContext codegenLexicalContext = lc;

        function.accept(new NodeVisitor<LexicalContext>(new LexicalContext()) {

            private MethodEmitter sharedScopeCall(final IdentNode identNode, final int flags) {
                final Symbol symbol = identNode.getSymbol();
                int    scopeCallFlags = flags;
                method.loadCompilerConstant(SCOPE);
                if (isFastScope(symbol)) {
                    method.load(getScopeProtoDepth(currentBlock, symbol));
                    scopeCallFlags |= CALLSITE_FAST_SCOPE;
                } else {
                    method.load(-1); // Bypass fast-scope code in shared callsite
                }
                loadArgs(args);
                final Type[] paramTypes = method.getTypesFromStack(args.size());
                final SharedScopeCall scopeCall = codegenLexicalContext.getScopeCall(unit, symbol, identNode.getType(), callNodeType, paramTypes, scopeCallFlags);
                return scopeCall.generateInvoke(method);
            }

            private void scopeCall(final IdentNode node, final int flags) {
                load(node, Type.OBJECT); // Type.OBJECT as foo() makes no sense if foo == 3
                // ScriptFunction will see CALLSITE_SCOPE and will bind scope accordingly.
                method.loadNull(); //the 'this'
                method.dynamicCall(callNodeType, 2 + loadArgs(args), flags);
            }

            private void evalCall(final IdentNode node, final int flags) {
                load(node, Type.OBJECT); // Type.OBJECT as foo() makes no sense if foo == 3

                final Label not_eval  = new Label("not_eval");
                final Label eval_done = new Label("eval_done");

                // check if this is the real built-in eval
                method.dup();
                globalIsEval();

                method.ifeq(not_eval);
                // We don't need ScriptFunction object for 'eval'
                method.pop();

                method.loadCompilerConstant(SCOPE); // Load up self (scope).

                final CallNode.EvalArgs evalArgs = callNode.getEvalArgs();
                // load evaluated code
                load(evalArgs.getCode(), Type.OBJECT);
                // load second and subsequent args for side-effect
                final List<Expression> args = callNode.getArgs();
                final int numArgs = args.size();
                for (int i = 1; i < numArgs; i++) {
                    load(args.get(i)).pop();
                }
                // special/extra 'eval' arguments
                load(evalArgs.getThis());
                method.load(evalArgs.getLocation());
                method.load(evalArgs.getStrictMode());
                method.convert(Type.OBJECT);

                // direct call to Global.directEval
                globalDirectEval();
                method.convert(callNodeType);
                method._goto(eval_done);

                method.label(not_eval);
                // This is some scope 'eval' or global eval replaced by user
                // but not the built-in ECMAScript 'eval' function call
                method.loadNull();
                method.dynamicCall(callNodeType, 2 + loadArgs(args), flags);

                method.label(eval_done);
            }

            @Override
            public boolean enterIdentNode(final IdentNode node) {
                final Symbol symbol = node.getSymbol();

                if (symbol.isScope()) {
                    final int flags = getCallSiteFlags() | CALLSITE_SCOPE;
                    final int useCount = symbol.getUseCount();

                    // Threshold for generating shared scope callsite is lower for fast scope symbols because we know
                    // we can dial in the correct scope. However, we also need to enable it for non-fast scopes to
                    // support huge scripts like mandreel.js.
                    if (callNode.isEval()) {
                        evalCall(node, flags);
                    } else if (useCount <= SharedScopeCall.FAST_SCOPE_CALL_THRESHOLD
                            || (!isFastScope(symbol) && useCount <= SharedScopeCall.SLOW_SCOPE_CALL_THRESHOLD)
                            || CodeGenerator.this.lc.inDynamicScope()) {
                        scopeCall(node, flags);
                    } else {
                        sharedScopeCall(node, flags);
                    }
                    assert method.peekType().equals(callNodeType) : method.peekType() + "!=" + callNode.getType();
                } else {
                    enterDefault(node);
                }

                return false;
            }

            @Override
            public boolean enterAccessNode(final AccessNode node) {
                load(node.getBase(), Type.OBJECT);
                method.dup();
                method.dynamicGet(node.getType(), node.getProperty().getName(), getCallSiteFlags(), true);
                method.swap();
                method.dynamicCall(callNodeType, 2 + loadArgs(args), getCallSiteFlags());

                return false;
            }

            @Override
            public boolean enterFunctionNode(final FunctionNode origCallee) {
                // NOTE: visiting the callee will leave a constructed ScriptFunction object on the stack if
                // callee.needsCallee() == true
                final FunctionNode callee = (FunctionNode)origCallee.accept(CodeGenerator.this);

                final boolean      isVarArg = callee.isVarArg();
                final int          argCount = isVarArg ? -1 : callee.getParameters().size();

                final String signature = new FunctionSignature(true, callee.needsCallee(), callee.getReturnType(), isVarArg ? null : callee.getParameters()).toString();

                if (callee.isStrict()) { // self is undefined
                    method.loadUndefined(Type.OBJECT);
                } else { // get global from scope (which is the self)
                    globalInstance();
                }
                loadArgs(args, signature, isVarArg, argCount);
                assert callee.getCompileUnit() != null : "no compile unit for " + callee.getName() + " " + Debug.id(callee) + " " + callNode;
                method.invokestatic(callee.getCompileUnit().getUnitClassName(), callee.getName(), signature);
                assert method.peekType().equals(callee.getReturnType()) : method.peekType() + " != " + callee.getReturnType();
                method.convert(callNodeType);
                return false;
            }

            @Override
            public boolean enterIndexNode(final IndexNode node) {
                load(node.getBase(), Type.OBJECT);
                method.dup();
                final Type indexType = node.getIndex().getType();
                if (indexType.isObject() || indexType.isBoolean()) {
                    load(node.getIndex(), Type.OBJECT); //TODO
                } else {
                    load(node.getIndex());
                }
                method.dynamicGetIndex(node.getType(), getCallSiteFlags(), true);
                method.swap();
                method.dynamicCall(callNodeType, 2 + loadArgs(args), getCallSiteFlags());

                return false;
            }

            @Override
            protected boolean enterDefault(final Node node) {
                // Load up function.
                load(function, Type.OBJECT); //TODO, e.g. booleans can be used as functions
                method.loadNull(); // ScriptFunction will figure out the correct this when it sees CALLSITE_SCOPE
                method.dynamicCall(callNodeType, 2 + loadArgs(args), getCallSiteFlags() | CALLSITE_SCOPE);

                return false;
            }
        });

        method.store(callNode.getSymbol());

        return false;
    }

    @Override
    public boolean enterContinueNode(final ContinueNode continueNode) {
        lineNumber(continueNode);

        final LoopNode continueTo = lc.getContinueTo(continueNode.getLabel());
        for (int i = 0; i < lc.getScopeNestingLevelTo(continueTo); i++) {
            closeWith();
        }
        method.splitAwareGoto(lc, continueTo.getContinueLabel());

        return false;
    }

    @Override
    public boolean enterEmptyNode(final EmptyNode emptyNode) {
        lineNumber(emptyNode);

        return false;
    }

    @Override
    public boolean enterExpressionStatement(final ExpressionStatement expressionStatement) {
        lineNumber(expressionStatement);

        expressionStatement.getExpression().accept(this);

        return false;
    }

    @Override
    public boolean enterBlockStatement(final BlockStatement blockStatement) {
        lineNumber(blockStatement);

        blockStatement.getBlock().accept(this);

        return false;
    }

    @Override
    public boolean enterForNode(final ForNode forNode) {
        lineNumber(forNode);

        if (forNode.isForIn()) {
            enterForIn(forNode);
        } else {
            enterFor(forNode);
        }

        return false;
    }

    private void enterFor(final ForNode forNode) {
        final Expression init   = forNode.getInit();
        final Expression test   = forNode.getTest();
        final Block      body   = forNode.getBody();
        final Expression modify = forNode.getModify();

        if (init != null) {
            init.accept(this);
        }

        final Label loopLabel = new Label("loop");
        final Label testLabel = new Label("test");

        method._goto(testLabel);
        method.label(loopLabel);
        body.accept(this);
        method.label(forNode.getContinueLabel());

        if (!body.isTerminal() && modify != null) {
            load(modify);
        }

        method.label(testLabel);
        if (test != null) {
            new BranchOptimizer(this, method).execute(test, loopLabel, true);
        } else {
            method._goto(loopLabel);
        }

        method.label(forNode.getBreakLabel());
    }

    private void enterForIn(final ForNode forNode) {
        final Block body   = forNode.getBody();
        final Expression  modify = forNode.getModify();

        final Symbol iter      = forNode.getIterator();
        final Label  loopLabel = new Label("loop");

        final Expression init = forNode.getInit();

        load(modify, Type.OBJECT);
        method.invoke(forNode.isForEach() ? ScriptRuntime.TO_VALUE_ITERATOR : ScriptRuntime.TO_PROPERTY_ITERATOR);
        method.store(iter);
        method._goto(forNode.getContinueLabel());
        method.label(loopLabel);

        new Store<Expression>(init) {
            @Override
            protected void storeNonDiscard() {
                return;
            }
            @Override
            protected void evaluate() {
                method.load(iter);
                method.invoke(interfaceCallNoLookup(Iterator.class, "next", Object.class));
            }
        }.store();

        body.accept(this);

        method.label(forNode.getContinueLabel());
        method.load(iter);
        method.invoke(interfaceCallNoLookup(Iterator.class, "hasNext", boolean.class));
        method.ifne(loopLabel);
        method.label(forNode.getBreakLabel());
    }

    /**
     * Initialize the slots in a frame to undefined.
     *
     * @param block block with local vars.
     */
    private void initLocals(final Block block) {
        lc.nextFreeSlot(block);

        final boolean isFunctionBody = lc.isFunctionBody();

        final FunctionNode function = lc.getCurrentFunction();
        if (isFunctionBody) {
            if(method.hasScope()) {
                if (function.needsParentScope()) {
                    method.loadCompilerConstant(CALLEE);
                    method.invoke(ScriptFunction.GET_SCOPE);
                } else {
                    assert function.hasScopeBlock();
                    method.loadNull();
                }
                method.storeCompilerConstant(SCOPE);
            }
            if (function.needsArguments()) {
                initArguments(function);
            }
        }

        /*
         * Determine if block needs scope, if not, just do initSymbols for this block.
         */
        if (block.needsScope()) {
            /*
             * Determine if function is varargs and consequently variables have to
             * be in the scope.
             */
            final boolean varsInScope = function.allVarsInScope();

            // TODO for LET we can do better: if *block* does not contain any eval/with, we don't need its vars in scope.

            final List<String> nameList = new ArrayList<>();
            final List<Symbol> locals   = new ArrayList<>();

            // Initalize symbols and values
            final List<Symbol> newSymbols = new ArrayList<>();
            final List<Symbol> values     = new ArrayList<>();

            final boolean hasArguments = function.needsArguments();

            for (final Symbol symbol : block.getSymbols()) {

                if (symbol.isInternal() || symbol.isThis() || symbol.isTemp()) {
                    continue;
                }

                if (symbol.isVar()) {
                    if (varsInScope || symbol.isScope()) {
                        nameList.add(symbol.getName());
                        newSymbols.add(symbol);
                        values.add(null);
                        assert symbol.isScope()   : "scope for " + symbol + " should have been set in Lower already " + function.getName();
                        assert !symbol.hasSlot()  : "slot for " + symbol + " should have been removed in Lower already" + function.getName();
                    } else {
                        assert symbol.hasSlot() : symbol + " should have a slot only, no scope";
                        locals.add(symbol);
                    }
                } else if (symbol.isParam() && (varsInScope || hasArguments || symbol.isScope())) {
                    nameList.add(symbol.getName());
                    newSymbols.add(symbol);
                    values.add(hasArguments ? null : symbol);
                    assert symbol.isScope()   : "scope for " + symbol + " should have been set in Lower already " + function.getName() + " varsInScope="+varsInScope+" hasArguments="+hasArguments+" symbol.isScope()=" + symbol.isScope();
                    assert !(hasArguments && symbol.hasSlot())  : "slot for " + symbol + " should have been removed in Lower already " + function.getName();
                }
            }

            // we may have locals that need to be initialized
            initSymbols(locals);

            /*
             * Create a new object based on the symbols and values, generate
             * bootstrap code for object
             */
            new FieldObjectCreator<Symbol>(this, nameList, newSymbols, values, true, hasArguments) {
                @Override
                protected void loadValue(final Symbol value) {
                    method.load(value);
                }
            }.makeObject(method);

            // runScript(): merge scope into global
            if (isFunctionBody && function.isProgram()) {
                method.invoke(ScriptRuntime.MERGE_SCOPE);
            }

            method.storeCompilerConstant(SCOPE);
        } else {
            // Since we don't have a scope, parameters didn't get assigned array indices by the FieldObjectCreator, so
            // we need to assign them separately here.
            int nextParam = 0;
            if (isFunctionBody && function.isVarArg()) {
                for (final IdentNode param : function.getParameters()) {
                    param.getSymbol().setFieldIndex(nextParam++);
                }
            }

            initSymbols(block.getSymbols());
        }

        // Debugging: print symbols? @see --print-symbols flag
        printSymbols(block, (isFunctionBody ? "Function " : "Block in ") + (function.getIdent() == null ? "<anonymous>" : function.getIdent().getName()));
    }

    private void initArguments(final FunctionNode function) {
        method.loadCompilerConstant(VARARGS);
        if (function.needsCallee()) {
            method.loadCompilerConstant(CALLEE);
        } else {
            // If function is strict mode, "arguments.callee" is not populated, so we don't necessarily need the
            // caller.
            assert function.isStrict();
            method.loadNull();
        }
        method.load(function.getParameters().size());
        globalAllocateArguments();
        method.storeCompilerConstant(ARGUMENTS);
    }

    @Override
    public boolean enterFunctionNode(final FunctionNode functionNode) {
        if (functionNode.isLazy()) {
            // Must do it now; can't postpone it until leaveFunctionNode()
            newFunctionObject(functionNode, functionNode);
            return false;
        }

        final String fnName = functionNode.getName();
        // NOTE: we only emit the method for a function with the given name once. We can have multiple functions with
        // the same name as a result of inlining finally blocks. However, in the future -- with type specialization,
        // notably -- we might need to check for both name *and* signature. Of course, even that might not be
        // sufficient; the function might have a code dependency on the type of the variables in its enclosing scopes,
        // and the type of such a variable can be different in catch and finally blocks. So, in the future we will have
        // to decide to either generate a unique method for each inlined copy of the function, maybe figure out its
        // exact type closure and deduplicate based on that, or just decide that functions in finally blocks aren't
        // worth it, and generate one method with most generic type closure.
        if(!emittedMethods.contains(fnName)) {
            LOG.info("=== BEGIN ", fnName);

            assert functionNode.getCompileUnit() != null : "no compile unit for " + fnName + " " + Debug.id(functionNode);
            unit = lc.pushCompileUnit(functionNode.getCompileUnit());
            assert lc.hasCompileUnits();

            method = lc.pushMethodEmitter(unit.getClassEmitter().method(functionNode));
            // new method - reset last line number
            lastLineNumber = -1;
            // Mark end for variable tables.
            method.begin();
        }

        return true;
    }

    @Override
    public Node leaveFunctionNode(final FunctionNode functionNode) {
        try {
            if(emittedMethods.add(functionNode.getName())) {
                method.end(); // wrap up this method
                unit   = lc.popCompileUnit(functionNode.getCompileUnit());
                method = lc.popMethodEmitter(method);
                LOG.info("=== END ", functionNode.getName());
            }

            final FunctionNode newFunctionNode = functionNode.setState(lc, CompilationState.EMITTED);
            newFunctionObject(newFunctionNode, functionNode);
            return newFunctionNode;
        } catch (final Throwable t) {
            Context.printStackTrace(t);
            final VerifyError e = new VerifyError("Code generation bug in \"" + functionNode.getName() + "\": likely stack misaligned: " + t + " " + functionNode.getSource().getName());
            e.initCause(t);
            throw e;
        }
    }

    @Override
    public boolean enterIdentNode(final IdentNode identNode) {
        return false;
    }

    @Override
    public boolean enterIfNode(final IfNode ifNode) {
        lineNumber(ifNode);

        final Expression test = ifNode.getTest();
        final Block pass = ifNode.getPass();
        final Block fail = ifNode.getFail();

        final Label failLabel  = new Label("if_fail");
        final Label afterLabel = fail == null ? failLabel : new Label("if_done");

        new BranchOptimizer(this, method).execute(test, failLabel, false);

        boolean passTerminal = false;
        boolean failTerminal = false;

        pass.accept(this);
        if (!pass.hasTerminalFlags()) {
            method._goto(afterLabel); //don't fallthru to fail block
        } else {
            passTerminal = pass.isTerminal();
        }

        if (fail != null) {
            method.label(failLabel);
            fail.accept(this);
            failTerminal = fail.isTerminal();
        }

        //if if terminates, put the after label there
        if (!passTerminal || !failTerminal) {
            method.label(afterLabel);
        }

        return false;
    }

    @Override
    public boolean enterIndexNode(final IndexNode indexNode) {
        load(indexNode);
        return false;
    }

    private void lineNumber(final Statement statement) {
        lineNumber(statement.getLineNumber());
    }

    private void lineNumber(int lineNumber) {
        if (lineNumber != lastLineNumber) {
            method.lineNumber(lineNumber);
        }
        lastLineNumber = lineNumber;
    }

    /**
     * Load a list of nodes as an array of a specific type
     * The array will contain the visited nodes.
     *
     * @param arrayLiteralNode the array of contents
     * @param arrayType        the type of the array, e.g. ARRAY_NUMBER or ARRAY_OBJECT
     *
     * @return the method generator that was used
     */
    private MethodEmitter loadArray(final ArrayLiteralNode arrayLiteralNode, final ArrayType arrayType) {
        assert arrayType == Type.INT_ARRAY || arrayType == Type.LONG_ARRAY || arrayType == Type.NUMBER_ARRAY || arrayType == Type.OBJECT_ARRAY;

        final Expression[]    nodes    = arrayLiteralNode.getValue();
        final Object          presets  = arrayLiteralNode.getPresets();
        final int[]           postsets = arrayLiteralNode.getPostsets();
        final Class<?>        type     = arrayType.getTypeClass();
        final List<ArrayUnit> units    = arrayLiteralNode.getUnits();

        loadConstant(presets);

        final Type elementType = arrayType.getElementType();

        if (units != null) {
            final MethodEmitter savedMethod     = method;
            final FunctionNode  currentFunction = lc.getCurrentFunction();

            for (final ArrayUnit arrayUnit : units) {
                unit = lc.pushCompileUnit(arrayUnit.getCompileUnit());

                final String className = unit.getUnitClassName();
                final String name      = currentFunction.uniqueName(SPLIT_PREFIX.symbolName());
                final String signature = methodDescriptor(type, ScriptFunction.class, Object.class, ScriptObject.class, type);

                final MethodEmitter me = unit.getClassEmitter().method(EnumSet.of(Flag.PUBLIC, Flag.STATIC), name, signature);
                method = lc.pushMethodEmitter(me);

                method.setFunctionNode(currentFunction);
                method.begin();

                fixScopeSlot(currentFunction);

                method.load(arrayType, SPLIT_ARRAY_ARG.slot());

                for (int i = arrayUnit.getLo(); i < arrayUnit.getHi(); i++) {
                    storeElement(nodes, elementType, postsets[i]);
                }

                method._return();
                method.end();
                method = lc.popMethodEmitter(me);

                assert method == savedMethod;
                method.loadCompilerConstant(CALLEE);
                method.swap();
                method.loadCompilerConstant(THIS);
                method.swap();
                method.loadCompilerConstant(SCOPE);
                method.swap();
                method.invokestatic(className, name, signature);

                unit = lc.popCompileUnit(unit);
            }

            return method;
        }

        for (final int postset : postsets) {
            storeElement(nodes, elementType, postset);
        }

        return method;
    }

    private void storeElement(final Expression[] nodes, final Type elementType, final int index) {
        method.dup();
        method.load(index);

        final Expression element = nodes[index];

        if (element == null) {
            method.loadEmpty(elementType);
        } else {
            load(element, elementType);
        }

        method.arraystore();
    }

    private MethodEmitter loadArgsArray(final List<Expression> args) {
        final Object[] array = new Object[args.size()];
        loadConstant(array);

        for (int i = 0; i < args.size(); i++) {
            method.dup();
            method.load(i);
            load(args.get(i), Type.OBJECT); //has to be upcast to object or we fail
            method.arraystore();
        }

        return method;
    }

    /**
     * Load a constant from the constant array. This is only public to be callable from the objects
     * subpackage. Do not call directly.
     *
     * @param string string to load
     */
    void loadConstant(final String string) {
        final String       unitClassName = unit.getUnitClassName();
        final ClassEmitter classEmitter  = unit.getClassEmitter();
        final int          index         = compiler.getConstantData().add(string);

        method.load(index);
        method.invokestatic(unitClassName, GET_STRING.symbolName(), methodDescriptor(String.class, int.class));
        classEmitter.needGetConstantMethod(String.class);
    }

    /**
     * Load a constant from the constant array. This is only public to be callable from the objects
     * subpackage. Do not call directly.
     *
     * @param object object to load
     */
    void loadConstant(final Object object) {
        final String       unitClassName = unit.getUnitClassName();
        final ClassEmitter classEmitter  = unit.getClassEmitter();
        final int          index         = compiler.getConstantData().add(object);
        final Class<?>     cls           = object.getClass();

        if (cls == PropertyMap.class) {
            method.load(index);
            method.invokestatic(unitClassName, GET_MAP.symbolName(), methodDescriptor(PropertyMap.class, int.class));
            classEmitter.needGetConstantMethod(PropertyMap.class);
        } else if (cls.isArray()) {
            method.load(index);
            final String methodName = ClassEmitter.getArrayMethodName(cls);
            method.invokestatic(unitClassName, methodName, methodDescriptor(cls, int.class));
            classEmitter.needGetConstantMethod(cls);
        } else {
            method.loadConstants().load(index).arrayload();
            if (object instanceof ArrayData) {
                // avoid cast to non-public ArrayData subclass
                method.checkcast(ArrayData.class);
                method.invoke(virtualCallNoLookup(ArrayData.class, "copy", ArrayData.class));
            } else if (cls != Object.class) {
                method.checkcast(cls);
            }
        }
    }

    // literal values
    private MethodEmitter loadLiteral(final LiteralNode<?> node, final Type type) {
        final Object value = node.getValue();

        if (value == null) {
            method.loadNull();
        } else if (value instanceof Undefined) {
            method.loadUndefined(Type.OBJECT);
        } else if (value instanceof String) {
            final String string = (String)value;

            if (string.length() > (MethodEmitter.LARGE_STRING_THRESHOLD / 3)) { // 3 == max bytes per encoded char
                loadConstant(string);
            } else {
                method.load(string);
            }
        } else if (value instanceof RegexToken) {
            loadRegex((RegexToken)value);
        } else if (value instanceof Boolean) {
            method.load((Boolean)value);
        } else if (value instanceof Integer) {
            if(type.isEquivalentTo(Type.NUMBER)) {
                method.load(((Integer)value).doubleValue());
            } else if(type.isEquivalentTo(Type.LONG)) {
                method.load(((Integer)value).longValue());
            } else {
                method.load((Integer)value);
            }
        } else if (value instanceof Long) {
            if(type.isEquivalentTo(Type.NUMBER)) {
                method.load(((Long)value).doubleValue());
            } else {
                method.load((Long)value);
            }
        } else if (value instanceof Double) {
            method.load((Double)value);
        } else if (node instanceof ArrayLiteralNode) {
            final ArrayLiteralNode arrayLiteral = (ArrayLiteralNode)node;
            final ArrayType atype = arrayLiteral.getArrayType();
            loadArray(arrayLiteral, atype);
            globalAllocateArray(atype);
        } else {
            assert false : "Unknown literal for " + node.getClass() + " " + value.getClass() + " " + value;
        }

        return method;
    }

    private MethodEmitter loadRegexToken(final RegexToken value) {
        method.load(value.getExpression());
        method.load(value.getOptions());
        return globalNewRegExp();
    }

    private MethodEmitter loadRegex(final RegexToken regexToken) {
        if (regexFieldCount > MAX_REGEX_FIELDS) {
            return loadRegexToken(regexToken);
        }
        // emit field
        final String       regexName    = lc.getCurrentFunction().uniqueName(REGEX_PREFIX.symbolName());
        final ClassEmitter classEmitter = unit.getClassEmitter();

        classEmitter.field(EnumSet.of(PRIVATE, STATIC), regexName, Object.class);
        regexFieldCount++;

        // get field, if null create new regex, finally clone regex object
        method.getStatic(unit.getUnitClassName(), regexName, typeDescriptor(Object.class));
        method.dup();
        final Label cachedLabel = new Label("cached");
        method.ifnonnull(cachedLabel);

        method.pop();
        loadRegexToken(regexToken);
        method.dup();
        method.putStatic(unit.getUnitClassName(), regexName, typeDescriptor(Object.class));

        method.label(cachedLabel);
        globalRegExpCopy();

        return method;
    }

    @Override
    public boolean enterLiteralNode(final LiteralNode<?> literalNode) {
        return enterLiteralNode(literalNode, literalNode.getType());
    }

    private boolean enterLiteralNode(final LiteralNode<?> literalNode, final Type type) {
        assert literalNode.getSymbol() != null : literalNode + " has no symbol";
        loadLiteral(literalNode, type).convert(type).store(literalNode.getSymbol());
        return false;
    }

    @Override
    public boolean enterObjectNode(final ObjectNode objectNode) {
        final List<PropertyNode> elements = objectNode.getElements();

        final List<String>     keys    = new ArrayList<>();
        final List<Symbol>     symbols = new ArrayList<>();
        final List<Expression> values  = new ArrayList<>();

        boolean hasGettersSetters = false;
        Expression protoNode = null;

        for (PropertyNode propertyNode: elements) {
            final Expression   value        = propertyNode.getValue();
            final String       key          = propertyNode.getKeyName();
            final Symbol       symbol       = value == null ? null : propertyNode.getKey().getSymbol();

            if (value == null) {
                hasGettersSetters = true;
            } else if (key.equals(ScriptObject.PROTO_PROPERTY_NAME)) {
                protoNode = value;
                continue;
            }

            keys.add(key);
            symbols.add(symbol);
            values.add(value);
        }

        if (elements.size() > OBJECT_SPILL_THRESHOLD) {
            new SpillObjectCreator(this, keys, symbols, values).makeObject(method);
        } else {
            new FieldObjectCreator<Expression>(this, keys, symbols, values) {
                @Override
                protected void loadValue(final Expression node) {
                    load(node);
                }

                /**
                 * Ensure that the properties start out as object types so that
                 * we can do putfield initializations instead of dynamicSetIndex
                 * which would be the case to determine initial property type
                 * otherwise.
                 *
                 * Use case, it's very expensive to do a million var x = {a:obj, b:obj}
                 * just to have to invalidate them immediately on initialization
                 *
                 * see NASHORN-594
                 */
                @Override
                protected MapCreator newMapCreator(final Class<?> fieldObjectClass) {
                    return new MapCreator(fieldObjectClass, keys, symbols) {
                        @Override
                        protected int getPropertyFlags(final Symbol symbol, final boolean hasArguments) {
                            return super.getPropertyFlags(symbol, hasArguments) | Property.IS_ALWAYS_OBJECT;
                        }
                    };
                }

            }.makeObject(method);
        }

        method.dup();
        if (protoNode != null) {
            load(protoNode);
            method.invoke(ScriptObject.SET_PROTO_CHECK);
        } else {
            globalObjectPrototype();
            method.invoke(ScriptObject.SET_PROTO);
        }

        if (hasGettersSetters) {
            for (final PropertyNode propertyNode : elements) {
                final FunctionNode getter       = propertyNode.getGetter();
                final FunctionNode setter       = propertyNode.getSetter();

                if (getter == null && setter == null) {
                    continue;
                }

                method.dup().loadKey(propertyNode.getKey());

                if (getter == null) {
                    method.loadNull();
                } else {
                    getter.accept(this);
                }

                if (setter == null) {
                    method.loadNull();
                } else {
                    setter.accept(this);
                }

                method.invoke(ScriptObject.SET_USER_ACCESSORS);
            }
        }

        method.store(objectNode.getSymbol());
        return false;
    }

    @Override
    public boolean enterReturnNode(final ReturnNode returnNode) {
        lineNumber(returnNode);

        method.registerReturn();

        final Type returnType = lc.getCurrentFunction().getReturnType();

        final Expression expression = returnNode.getExpression();
        if (expression != null) {
            load(expression);
        } else {
            method.loadUndefined(returnType);
        }

        method._return(returnType);

        return false;
    }

    private static boolean isNullLiteral(final Node node) {
        return node instanceof LiteralNode<?> && ((LiteralNode<?>) node).isNull();
    }

    private boolean nullCheck(final RuntimeNode runtimeNode, final List<Expression> args, final String signature) {
        final Request request = runtimeNode.getRequest();

        if (!Request.isEQ(request) && !Request.isNE(request)) {
            return false;
        }

        assert args.size() == 2 : "EQ or NE or TYPEOF need two args";

        Expression lhs = args.get(0);
        Expression rhs = args.get(1);

        if (isNullLiteral(lhs)) {
            final Expression tmp = lhs;
            lhs = rhs;
            rhs = tmp;
        }

        // this is a null literal check, so if there is implicit coercion
        // involved like {D}x=null, we will fail - this is very rare
        if (isNullLiteral(rhs) && lhs.getType().isObject()) {
            final Label trueLabel  = new Label("trueLabel");
            final Label falseLabel = new Label("falseLabel");
            final Label endLabel   = new Label("end");

            load(lhs);
            method.dup();
            if (Request.isEQ(request)) {
                method.ifnull(trueLabel);
            } else if (Request.isNE(request)) {
                method.ifnonnull(trueLabel);
            } else {
                assert false : "Invalid request " + request;
            }

            method.label(falseLabel);
            load(rhs);
            method.invokestatic(CompilerConstants.className(ScriptRuntime.class), request.toString(), signature);
            method._goto(endLabel);

            method.label(trueLabel);
            // if NE (not strict) this can be "undefined != null" which is supposed to be false
            if (request == Request.NE) {
                method.loadUndefined(Type.OBJECT);
                final Label isUndefined = new Label("isUndefined");
                final Label afterUndefinedCheck = new Label("afterUndefinedCheck");
                method.if_acmpeq(isUndefined);
                // not undefined
                method.load(true);
                method._goto(afterUndefinedCheck);
                method.label(isUndefined);
                method.load(false);
                method.label(afterUndefinedCheck);
            } else {
                method.pop();
                method.load(true);
            }
            method.label(endLabel);
            method.convert(runtimeNode.getType());
            method.store(runtimeNode.getSymbol());

            return true;
        }

        return false;
    }

    private boolean specializationCheck(final RuntimeNode.Request request, final Expression node, final List<Expression> args) {
        if (!request.canSpecialize()) {
            return false;
        }

        assert args.size() == 2;
        final Type returnType = node.getType();

        load(args.get(0));
        load(args.get(1));

        Request finalRequest = request;

        //if the request is a comparison, i.e. one that can be reversed
        //it keeps its semantic, but make sure that the object comes in
        //last
        final Request reverse = Request.reverse(request);
        if (method.peekType().isObject() && reverse != null) { //rhs is object
            if (!method.peekType(1).isObject()) { //lhs is not object
                method.swap(); //prefer object as lhs
                finalRequest = reverse;
            }
        }

        method.dynamicRuntimeCall(
                new SpecializedRuntimeNode(
                    finalRequest,
                    new Type[] {
                        method.peekType(1),
                        method.peekType()
                    },
                    returnType).getInitialName(),
                returnType,
                finalRequest);

        method.convert(node.getType());
        method.store(node.getSymbol());

        return true;
    }

    private static boolean isReducible(final Request request) {
        return Request.isComparison(request) || request == Request.ADD;
    }

    @Override
    public boolean enterRuntimeNode(final RuntimeNode runtimeNode) {
        /*
         * First check if this should be something other than a runtime node
         * AccessSpecializer might have changed the type
         *
         * TODO - remove this - Access Specializer will always know after Attr/Lower
         */
        final List<Expression> args = runtimeNode.getArgs();
        if (runtimeNode.isPrimitive() && !runtimeNode.isFinal() && isReducible(runtimeNode.getRequest())) {
            final Expression lhs = args.get(0);
            assert args.size() > 1 : runtimeNode + " must have two args";
            final Expression rhs = args.get(1);

            final Type   type   = runtimeNode.getType();
            final Symbol symbol = runtimeNode.getSymbol();

            switch (runtimeNode.getRequest()) {
            case EQ:
            case EQ_STRICT:
                return enterCmp(lhs, rhs, Condition.EQ, type, symbol);
            case NE:
            case NE_STRICT:
                return enterCmp(lhs, rhs, Condition.NE, type, symbol);
            case LE:
                return enterCmp(lhs, rhs, Condition.LE, type, symbol);
            case LT:
                return enterCmp(lhs, rhs, Condition.LT, type, symbol);
            case GE:
                return enterCmp(lhs, rhs, Condition.GE, type, symbol);
            case GT:
                return enterCmp(lhs, rhs, Condition.GT, type, symbol);
            case ADD:
                Type widest = Type.widest(lhs.getType(), rhs.getType());
                load(lhs, widest);
                load(rhs, widest);
                method.add();
                method.convert(type);
                method.store(symbol);
                return false;
            default:
                // it's ok to send this one on with only primitive arguments, maybe INSTANCEOF(true, true) or similar
                // assert false : runtimeNode + " has all primitive arguments. This is an inconsistent state";
                break;
            }
        }

        if (nullCheck(runtimeNode, args, new FunctionSignature(false, false, runtimeNode.getType(), args).toString())) {
           return false;
        }

        if (!runtimeNode.isFinal() && specializationCheck(runtimeNode.getRequest(), runtimeNode, args)) {
           return false;
        }

        for (final Expression arg : args) {
            load(arg, Type.OBJECT);
        }

        method.invokestatic(
            CompilerConstants.className(ScriptRuntime.class),
            runtimeNode.getRequest().toString(),
            new FunctionSignature(
                false,
                false,
                runtimeNode.getType(),
                args.size()).toString());
        method.convert(runtimeNode.getType());
        method.store(runtimeNode.getSymbol());

        return false;
    }

    @Override
    public boolean enterSplitNode(final SplitNode splitNode) {
        final CompileUnit splitCompileUnit = splitNode.getCompileUnit();

        final FunctionNode fn   = lc.getCurrentFunction();
        final String className  = splitCompileUnit.getUnitClassName();
        final String name       = splitNode.getName();

        final Class<?>   rtype          = fn.getReturnType().getTypeClass();
        final boolean    needsArguments = fn.needsArguments();
        final Class<?>[] ptypes         = needsArguments ?
                new Class<?>[] {ScriptFunction.class, Object.class, ScriptObject.class, Object.class} :
                new Class<?>[] {ScriptFunction.class, Object.class, ScriptObject.class};

        final MethodEmitter caller = method;
        unit = lc.pushCompileUnit(splitCompileUnit);

        final Call splitCall = staticCallNoLookup(
            className,
            name,
            methodDescriptor(rtype, ptypes));

        final MethodEmitter splitEmitter =
                splitCompileUnit.getClassEmitter().method(
                        splitNode,
                        name,
                        rtype,
                        ptypes);

        method = lc.pushMethodEmitter(splitEmitter);
        method.setFunctionNode(fn);

        assert fn.needsCallee() : "split function should require callee";
        caller.loadCompilerConstant(CALLEE);
        caller.loadCompilerConstant(THIS);
        caller.loadCompilerConstant(SCOPE);
        if (needsArguments) {
            caller.loadCompilerConstant(ARGUMENTS);
        }
        caller.invoke(splitCall);
        caller.storeCompilerConstant(RETURN);

        method.begin();
        // Copy scope to its target slot as first thing because the original slot could be used by return symbol.
        fixScopeSlot(fn);

        method.loadUndefined(fn.getReturnType());
        method.storeCompilerConstant(RETURN);

        return true;
    }

    private void fixScopeSlot(final FunctionNode functionNode) {
        // TODO hack to move the scope to the expected slot (needed because split methods reuse the same slots as the root method)
        if (functionNode.compilerConstant(SCOPE).getSlot() != SCOPE.slot()) {
            method.load(Type.typeFor(ScriptObject.class), SCOPE.slot());
            method.storeCompilerConstant(SCOPE);
        }
    }

    @Override
    public Node leaveSplitNode(final SplitNode splitNode) {
        assert method instanceof SplitMethodEmitter;
        final boolean     hasReturn = method.hasReturn();
        final List<Label> targets   = method.getExternalTargets();

        try {
            // Wrap up this method.

            method.loadCompilerConstant(RETURN);
            method._return(lc.getCurrentFunction().getReturnType());
            method.end();

            unit   = lc.popCompileUnit(splitNode.getCompileUnit());
            method = lc.popMethodEmitter(method);

        } catch (final Throwable t) {
            Context.printStackTrace(t);
            final VerifyError e = new VerifyError("Code generation bug in \"" + splitNode.getName() + "\": likely stack misaligned: " + t + " " + lc.getCurrentFunction().getSource().getName());
            e.initCause(t);
            throw e;
        }

        // Handle return from split method if there was one.
        final MethodEmitter caller = method;
        final int     targetCount = targets.size();

        //no external jump targets or return in switch node
        if (!hasReturn && targets.isEmpty()) {
            return splitNode;
        }

        caller.loadCompilerConstant(SCOPE);
        caller.checkcast(Scope.class);
        caller.invoke(Scope.GET_SPLIT_STATE);

        final Label breakLabel = new Label("no_split_state");
        // Split state is -1 for no split state, 0 for return, 1..n+1 for break/continue

        //the common case is that we don't need a switch
        if (targetCount == 0) {
            assert hasReturn;
            caller.ifne(breakLabel);
            //has to be zero
            caller.label(new Label("split_return"));
            caller.loadCompilerConstant(RETURN);
            caller._return(lc.getCurrentFunction().getReturnType());
            caller.label(breakLabel);
        } else {
            assert !targets.isEmpty();

            final int     low         = hasReturn ? 0 : 1;
            final int     labelCount  = targetCount + 1 - low;
            final Label[] labels      = new Label[labelCount];

            for (int i = 0; i < labelCount; i++) {
                labels[i] = new Label(i == 0 ? "split_return" : "split_" + targets.get(i - 1));
            }
            caller.tableswitch(low, targetCount, breakLabel, labels);
            for (int i = low; i <= targetCount; i++) {
                caller.label(labels[i - low]);
                if (i == 0) {
                    caller.loadCompilerConstant(RETURN);
                    caller._return(lc.getCurrentFunction().getReturnType());
                } else {
                    // Clear split state.
                    caller.loadCompilerConstant(SCOPE);
                    caller.checkcast(Scope.class);
                    caller.load(-1);
                    caller.invoke(Scope.SET_SPLIT_STATE);
                    caller.splitAwareGoto(lc, targets.get(i - 1));
                }
            }
            caller.label(breakLabel);
        }

        // If split has a return and caller is itself a split method it needs to propagate the return.
        if (hasReturn) {
            caller.setHasReturn();
        }

        return splitNode;
    }

    @Override
    public boolean enterSwitchNode(final SwitchNode switchNode) {
        lineNumber(switchNode);

        final Expression     expression  = switchNode.getExpression();
        final Symbol         tag         = switchNode.getTag();
        final boolean        allInteger  = tag.getSymbolType().isInteger();
        final List<CaseNode> cases       = switchNode.getCases();
        final CaseNode       defaultCase = switchNode.getDefaultCase();
        final Label          breakLabel  = switchNode.getBreakLabel();

        Label defaultLabel = breakLabel;
        boolean hasDefault = false;

        if (defaultCase != null) {
            defaultLabel = defaultCase.getEntry();
            hasDefault = true;
        }

        if (cases.isEmpty()) {
            // still evaluate expression for side-effects.
            load(expression).pop();
            method.label(breakLabel);
            return false;
        }

        if (allInteger) {
            // Tree for sorting values.
            final TreeMap<Integer, Label> tree = new TreeMap<>();

            // Build up sorted tree.
            for (final CaseNode caseNode : cases) {
                final Node test = caseNode.getTest();

                if (test != null) {
                    final Integer value = (Integer)((LiteralNode<?>)test).getValue();
                    final Label   entry = caseNode.getEntry();

                    // Take first duplicate.
                    if (!(tree.containsKey(value))) {
                        tree.put(value, entry);
                    }
                }
            }

            // Copy values and labels to arrays.
            final int       size   = tree.size();
            final Integer[] values = tree.keySet().toArray(new Integer[size]);
            final Label[]   labels = tree.values().toArray(new Label[size]);

            // Discern low, high and range.
            final int lo    = values[0];
            final int hi    = values[size - 1];
            final int range = hi - lo + 1;

            // Find an unused value for default.
            int deflt = Integer.MIN_VALUE;
            for (final int value : values) {
                if (deflt == value) {
                    deflt++;
                } else if (deflt < value) {
                    break;
                }
            }

            // Load switch expression.
            load(expression);
            final Type type = expression.getType();

            // If expression not int see if we can convert, if not use deflt to trigger default.
            if (!type.isInteger()) {
                method.load(deflt);
                final Class<?> exprClass = type.getTypeClass();
                method.invoke(staticCallNoLookup(ScriptRuntime.class, "switchTagAsInt", int.class, exprClass.isPrimitive()? exprClass : Object.class, int.class));
            }

            // If reasonable size and not too sparse (80%), use table otherwise use lookup.
            if (range > 0 && range < 4096 && range < (size * 5 / 4)) {
                final Label[] table = new Label[range];
                Arrays.fill(table, defaultLabel);

                for (int i = 0; i < size; i++) {
                    final int value = values[i];
                    table[value - lo] = labels[i];
                }

                method.tableswitch(lo, hi, defaultLabel, table);
            } else {
                final int[] ints = new int[size];
                for (int i = 0; i < size; i++) {
                    ints[i] = values[i];
                }

                method.lookupswitch(defaultLabel, ints, labels);
            }
        } else {
            load(expression, Type.OBJECT);
            method.store(tag);

            for (final CaseNode caseNode : cases) {
                final Expression test = caseNode.getTest();

                if (test != null) {
                    method.load(tag);
                    load(test, Type.OBJECT);
                    method.invoke(ScriptRuntime.EQ_STRICT);
                    method.ifne(caseNode.getEntry());
                }
            }

            method._goto(hasDefault ? defaultLabel : breakLabel);
        }

        for (final CaseNode caseNode : cases) {
            method.label(caseNode.getEntry());
            caseNode.getBody().accept(this);
        }

        if (!switchNode.isTerminal()) {
            method.label(breakLabel);
        }

        return false;
    }

    @Override
    public boolean enterThrowNode(final ThrowNode throwNode) {
        lineNumber(throwNode);

        if (throwNode.isSyntheticRethrow()) {
            //do not wrap whatever this is in an ecma exception, just rethrow it
            load(throwNode.getExpression());
            method.athrow();
            return false;
        }

        method._new(ECMAException.class).dup();

        final Source source     = lc.getCurrentFunction().getSource();

        final Expression expression = throwNode.getExpression();
        final int        position   = throwNode.position();
        final int        line       = throwNode.getLineNumber();
        final int        column     = source.getColumn(position);

        load(expression, Type.OBJECT);

        method.load(source.getName());
        method.load(line);
        method.load(column);
        method.invoke(ECMAException.THROW_INIT);

        method.athrow();

        return false;
    }

    @Override
    public boolean enterTryNode(final TryNode tryNode) {
        lineNumber(tryNode);

        final Block       body        = tryNode.getBody();
        final List<Block> catchBlocks = tryNode.getCatchBlocks();
        final Symbol      symbol      = tryNode.getException();
        final Label       entry       = new Label("try");
        final Label       recovery    = new Label("catch");
        final Label       exit        = tryNode.getExit();
        final Label       skip        = new Label("skip");

        method.label(entry);

        body.accept(this);

        if (!body.hasTerminalFlags()) {
            method._goto(skip);
        }

        method.label(exit);

        method._catch(recovery);
        method.store(symbol);

        for (int i = 0; i < catchBlocks.size(); i++) {
            final Block catchBlock = catchBlocks.get(i);

            //TODO this is very ugly - try not to call enter/leave methods directly
            //better to use the implicit lexical context scoping given by the visitor's
            //accept method.
            lc.push(catchBlock);
            enterBlock(catchBlock);

            final CatchNode  catchNode          = (CatchNode)catchBlocks.get(i).getStatements().get(0);
            final IdentNode  exception          = catchNode.getException();
            final Expression exceptionCondition = catchNode.getExceptionCondition();
            final Block      catchBody          = catchNode.getBody();

            new Store<IdentNode>(exception) {
                @Override
                protected void storeNonDiscard() {
                    return;
                }

                @Override
                protected void evaluate() {
                    if (catchNode.isSyntheticRethrow()) {
                        method.load(symbol);
                        return;
                    }
                    /*
                     * If caught object is an instance of ECMAException, then
                     * bind obj.thrown to the script catch var. Or else bind the
                     * caught object itself to the script catch var.
                     */
                    final Label notEcmaException = new Label("no_ecma_exception");
                    method.load(symbol).dup()._instanceof(ECMAException.class).ifeq(notEcmaException);
                    method.checkcast(ECMAException.class); //TODO is this necessary?
                    method.getField(ECMAException.THROWN);
                    method.label(notEcmaException);
                }
            }.store();

            final Label next;

            if (exceptionCondition != null) {
                next = new Label("next");
                load(exceptionCondition, Type.BOOLEAN).ifeq(next);
            } else {
                next = null;
            }

            catchBody.accept(this);

            if (i + 1 != catchBlocks.size() && !catchBody.hasTerminalFlags()) {
                method._goto(skip);
            }

            if (next != null) {
                if (i + 1 == catchBlocks.size()) {
                    // no next catch block - rethrow if condition failed
                    method._goto(skip);
                    method.label(next);
                    method.load(symbol).athrow();
                } else {
                    method.label(next);
                }
            }

            leaveBlock(catchBlock);
            lc.pop(catchBlock);
        }

        method.label(skip);
        method._try(entry, exit, recovery, Throwable.class);

        // Finally body is always inlined elsewhere so it doesn't need to be emitted

        return false;
    }

    @Override
    public boolean enterVarNode(final VarNode varNode) {

        final Expression init = varNode.getInit();

        if (init == null) {
            return false;
        }

        lineNumber(varNode);

        final IdentNode identNode = varNode.getName();
        final Symbol identSymbol = identNode.getSymbol();
        assert identSymbol != null : "variable node " + varNode + " requires a name with a symbol";

        assert method != null;

        final boolean needsScope = identSymbol.isScope();
        if (needsScope) {
            method.loadCompilerConstant(SCOPE);
        }

        if (needsScope) {
            load(init);
            int flags = CALLSITE_SCOPE | getCallSiteFlags();
            if (isFastScope(identSymbol)) {
                storeFastScopeVar(identSymbol, flags);
            } else {
                method.dynamicSet(identNode.getName(), flags);
            }
        } else {
            load(init, identNode.getType());
            method.store(identSymbol);
        }

        return false;
    }

    @Override
    public boolean enterWhileNode(final WhileNode whileNode) {
        final Expression test          = whileNode.getTest();
        final Block      body          = whileNode.getBody();
        final Label      breakLabel    = whileNode.getBreakLabel();
        final Label      continueLabel = whileNode.getContinueLabel();
        final boolean    isDoWhile     = whileNode.isDoWhile();
        final Label      loopLabel     = new Label("loop");

        if (!isDoWhile) {
            method._goto(continueLabel);
        }

        method.label(loopLabel);
        body.accept(this);
        if (!whileNode.isTerminal()) {
            method.label(continueLabel);
            lineNumber(whileNode);
            new BranchOptimizer(this, method).execute(test, loopLabel, true);
            method.label(breakLabel);
        }

        return false;
    }

    private void closeWith() {
        if (method.hasScope()) {
            method.loadCompilerConstant(SCOPE);
            method.invoke(ScriptRuntime.CLOSE_WITH);
            method.storeCompilerConstant(SCOPE);
        }
    }

    @Override
    public boolean enterWithNode(final WithNode withNode) {
        final Expression expression = withNode.getExpression();
        final Node       body       = withNode.getBody();

        // It is possible to have a "pathological" case where the with block does not reference *any* identifiers. It's
        // pointless, but legal. In that case, if nothing else in the method forced the assignment of a slot to the
        // scope object, its' possible that it won't have a slot assigned. In this case we'll only evaluate expression
        // for its side effect and visit the body, and not bother opening and closing a WithObject.
        final boolean hasScope = method.hasScope();

        final Label tryLabel;
        if (hasScope) {
            tryLabel = new Label("with_try");
            method.label(tryLabel);
            method.loadCompilerConstant(SCOPE);
        } else {
            tryLabel = null;
        }

        load(expression, Type.OBJECT);

        if (hasScope) {
            // Construct a WithObject if we have a scope
            method.invoke(ScriptRuntime.OPEN_WITH);
            method.storeCompilerConstant(SCOPE);
        } else {
            // We just loaded the expression for its side effect and to check
            // for null or undefined value.
            globalCheckObjectCoercible();
        }


        // Always process body
        body.accept(this);

        if (hasScope) {
            // Ensure we always close the WithObject
            final Label endLabel   = new Label("with_end");
            final Label catchLabel = new Label("with_catch");
            final Label exitLabel  = new Label("with_exit");

            if (!body.isTerminal()) {
                closeWith();
                method._goto(exitLabel);
            }

            method.label(endLabel);

            method._catch(catchLabel);
            closeWith();
            method.athrow();

            method.label(exitLabel);

            method._try(tryLabel, endLabel, catchLabel);
        }
        return false;
    }

    @Override
    public boolean enterADD(final UnaryNode unaryNode) {
        load(unaryNode.rhs(), unaryNode.getType());
        assert unaryNode.getType().isNumeric();
        method.store(unaryNode.getSymbol());
        return false;
    }

    @Override
    public boolean enterBIT_NOT(final UnaryNode unaryNode) {
        load(unaryNode.rhs(), Type.INT).load(-1).xor().store(unaryNode.getSymbol());
        return false;
    }

    @Override
    public boolean enterDECINC(final UnaryNode unaryNode) {
        final Expression rhs         = unaryNode.rhs();
        final Type       type        = unaryNode.getType();
        final TokenType  tokenType   = unaryNode.tokenType();
        final boolean    isPostfix   = tokenType == TokenType.DECPOSTFIX || tokenType == TokenType.INCPOSTFIX;
        final boolean    isIncrement = tokenType == TokenType.INCPREFIX || tokenType == TokenType.INCPOSTFIX;

        assert !type.isObject();

        new SelfModifyingStore<UnaryNode>(unaryNode, rhs) {

            @Override
            protected void evaluate() {
                load(rhs, type, true);
                if (!isPostfix) {
                    if (type.isInteger()) {
                        method.load(isIncrement ? 1 : -1);
                    } else if (type.isLong()) {
                        method.load(isIncrement ? 1L : -1L);
                    } else {
                        method.load(isIncrement ? 1.0 : -1.0);
                    }
                    method.add();
                }
            }

            @Override
            protected void storeNonDiscard() {
                super.storeNonDiscard();
                if (isPostfix) {
                    if (type.isInteger()) {
                        method.load(isIncrement ? 1 : -1);
                    } else if (type.isLong()) {
                        method.load(isIncrement ? 1L : 1L);
                    } else {
                        method.load(isIncrement ? 1.0 : -1.0);
                    }
                    method.add();
                }
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterDISCARD(final UnaryNode unaryNode) {
        final Expression rhs = unaryNode.rhs();

        lc.pushDiscard(rhs);
        load(rhs);

        if (lc.getCurrentDiscard() == rhs) {
            assert !rhs.isAssignment();
            method.pop();
            lc.popDiscard();
        }

        return false;
    }

    @Override
    public boolean enterNEW(final UnaryNode unaryNode) {
        final CallNode callNode = (CallNode)unaryNode.rhs();
        final List<Expression> args   = callNode.getArgs();

        // Load function reference.
        load(callNode.getFunction(), Type.OBJECT); // must detect type error

        method.dynamicNew(1 + loadArgs(args), getCallSiteFlags());
        method.store(unaryNode.getSymbol());

        return false;
    }

    @Override
    public boolean enterNOT(final UnaryNode unaryNode) {
        final Expression rhs = unaryNode.rhs();

        load(rhs, Type.BOOLEAN);

        final Label trueLabel  = new Label("true");
        final Label afterLabel = new Label("after");

        method.ifne(trueLabel);
        method.load(true);
        method._goto(afterLabel);
        method.label(trueLabel);
        method.load(false);
        method.label(afterLabel);
        method.store(unaryNode.getSymbol());

        return false;
    }

    @Override
    public boolean enterSUB(final UnaryNode unaryNode) {
        assert unaryNode.getType().isNumeric();
        load(unaryNode.rhs(), unaryNode.getType()).neg().store(unaryNode.getSymbol());
        return false;
    }

    @Override
    public boolean enterVOID(final UnaryNode unaryNode) {
        load(unaryNode.rhs()).pop();
        method.loadUndefined(Type.OBJECT);

        return false;
    }

    private void enterNumericAdd(final Expression lhs, final Expression rhs, final Type type, final Symbol symbol) {
        loadBinaryOperands(lhs, rhs, type);
        method.add(); //if the symbol is optimistic, it always needs to be written, not on the stack?
        method.store(symbol);
    }

    @Override
    public boolean enterADD(final BinaryNode binaryNode) {
        final Expression lhs = binaryNode.lhs();
        final Expression rhs = binaryNode.rhs();

        final Type type = binaryNode.getType();
        if (type.isNumeric()) {
            enterNumericAdd(lhs, rhs, type, binaryNode.getSymbol());
        } else {
            loadBinaryOperands(binaryNode);
            method.add();
            method.store(binaryNode.getSymbol());
        }

        return false;
    }

    private boolean enterAND_OR(final BinaryNode binaryNode) {
        final Expression lhs = binaryNode.lhs();
        final Expression rhs = binaryNode.rhs();

        final Label skip = new Label("skip");

        load(lhs, Type.OBJECT).dup().convert(Type.BOOLEAN);

        if (binaryNode.tokenType() == TokenType.AND) {
            method.ifeq(skip);
        } else {
            method.ifne(skip);
        }

        method.pop();
        load(rhs, Type.OBJECT);
        method.label(skip);
        method.store(binaryNode.getSymbol());

        return false;
    }

    @Override
    public boolean enterAND(final BinaryNode binaryNode) {
        return enterAND_OR(binaryNode);
    }

    @Override
    public boolean enterASSIGN(final BinaryNode binaryNode) {
        final Expression lhs = binaryNode.lhs();
        final Expression rhs = binaryNode.rhs();

        final Type lhsType = lhs.getType();
        final Type rhsType = rhs.getType();

        if (!lhsType.isEquivalentTo(rhsType)) {
            //this is OK if scoped, only locals are wrong
        }

        new Store<BinaryNode>(binaryNode, lhs) {
            @Override
            protected void evaluate() {
                if ((lhs instanceof IdentNode) && !lhs.getSymbol().isScope()) {
                    load(rhs, lhsType);
                } else {
                    load(rhs);
                }
            }
        }.store();

        return false;
    }

    /**
     * Helper class for assignment ops, e.g. *=, += and so on..
     */
    private abstract class AssignOp extends SelfModifyingStore<BinaryNode> {

        /** The type of the resulting operation */
        private final Type opType;

        /**
         * Constructor
         *
         * @param node the assign op node
         */
        AssignOp(final BinaryNode node) {
            this(node.getType(), node);
        }

        /**
         * Constructor
         *
         * @param opType type of the computation - overriding the type of the node
         * @param node the assign op node
         */
        AssignOp(final Type opType, final BinaryNode node) {
            super(node, node.lhs());
            this.opType = opType;
        }

        protected abstract void op();

        @Override
        protected void evaluate() {
            loadBinaryOperands(assignNode.lhs(), assignNode.rhs(), opType, true);
            op();
            method.convert(assignNode.getType());
        }
    }

    @Override
    public boolean enterASSIGN_ADD(final BinaryNode binaryNode) {
        assert RuntimeNode.Request.ADD.canSpecialize();
        final Type lhsType = binaryNode.lhs().getType();
        final Type rhsType = binaryNode.rhs().getType();
        final boolean specialize = binaryNode.getType() == Type.OBJECT;

        new AssignOp(binaryNode) {

            @Override
            protected void op() {
                if (specialize) {
                    method.dynamicRuntimeCall(
                            new SpecializedRuntimeNode(
                                Request.ADD,
                                new Type[] {
                                    lhsType,
                                    rhsType,
                                },
                                Type.OBJECT).getInitialName(),
                            Type.OBJECT,
                            Request.ADD);
                } else {
                    method.add();
                }
            }

            @Override
            protected void evaluate() {
                super.evaluate();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_BIT_AND(final BinaryNode binaryNode) {
        new AssignOp(Type.INT, binaryNode) {
            @Override
            protected void op() {
                method.and();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_BIT_OR(final BinaryNode binaryNode) {
        new AssignOp(Type.INT, binaryNode) {
            @Override
            protected void op() {
                method.or();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_BIT_XOR(final BinaryNode binaryNode) {
        new AssignOp(Type.INT, binaryNode) {
            @Override
            protected void op() {
                method.xor();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_DIV(final BinaryNode binaryNode) {
        new AssignOp(binaryNode) {
            @Override
            protected void op() {
                method.div();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_MOD(final BinaryNode binaryNode) {
        new AssignOp(binaryNode) {
            @Override
            protected void op() {
                method.rem();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_MUL(final BinaryNode binaryNode) {
        new AssignOp(binaryNode) {
            @Override
            protected void op() {
                method.mul();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_SAR(final BinaryNode binaryNode) {
        new AssignOp(Type.INT, binaryNode) {
            @Override
            protected void op() {
                method.sar();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_SHL(final BinaryNode binaryNode) {
        new AssignOp(Type.INT, binaryNode) {
            @Override
            protected void op() {
                method.shl();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_SHR(final BinaryNode binaryNode) {
        new AssignOp(Type.INT, binaryNode) {
            @Override
            protected void op() {
                method.shr();
                method.convert(Type.LONG).load(JSType.MAX_UINT).and();
            }
        }.store();

        return false;
    }

    @Override
    public boolean enterASSIGN_SUB(final BinaryNode binaryNode) {
        new AssignOp(binaryNode) {
            @Override
            protected void op() {
                method.sub();
            }
        }.store();

        return false;
    }

    /**
     * Helper class for binary arithmetic ops
     */
    private abstract class BinaryArith {

        protected abstract void op();

        protected void evaluate(final BinaryNode node) {
            loadBinaryOperands(node);
            op();
            method.store(node.getSymbol());
        }
    }

    @Override
    public boolean enterBIT_AND(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.and();
            }
        }.evaluate(binaryNode);

        return false;
    }

    @Override
    public boolean enterBIT_OR(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.or();
            }
        }.evaluate(binaryNode);

        return false;
    }

    @Override
    public boolean enterBIT_XOR(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.xor();
            }
        }.evaluate(binaryNode);

        return false;
    }

    private boolean enterComma(final BinaryNode binaryNode) {
        final Expression lhs = binaryNode.lhs();
        final Expression rhs = binaryNode.rhs();

        load(lhs);
        load(rhs);
        method.store(binaryNode.getSymbol());

        return false;
    }

    @Override
    public boolean enterCOMMARIGHT(final BinaryNode binaryNode) {
        return enterComma(binaryNode);
    }

    @Override
    public boolean enterCOMMALEFT(final BinaryNode binaryNode) {
        return enterComma(binaryNode);
    }

    @Override
    public boolean enterDIV(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.div();
            }
        }.evaluate(binaryNode);

        return false;
    }

    private boolean enterCmp(final Expression lhs, final Expression rhs, final Condition cond, final Type type, final Symbol symbol) {
        final Type lhsType = lhs.getType();
        final Type rhsType = rhs.getType();

        final Type widest = Type.widest(lhsType, rhsType);
        assert widest.isNumeric() || widest.isBoolean() : widest;

        loadBinaryOperands(lhs, rhs, widest);
        final Label trueLabel  = new Label("trueLabel");
        final Label afterLabel = new Label("skip");

        method.conditionalJump(cond, trueLabel);

        method.load(Boolean.FALSE);
        method._goto(afterLabel);
        method.label(trueLabel);
        method.load(Boolean.TRUE);
        method.label(afterLabel);

        method.convert(type);
        method.store(symbol);

        return false;
    }

    private boolean enterCmp(final BinaryNode binaryNode, final Condition cond) {
        return enterCmp(binaryNode.lhs(), binaryNode.rhs(), cond, binaryNode.getType(), binaryNode.getSymbol());
    }

    @Override
    public boolean enterEQ(final BinaryNode binaryNode) {
        return enterCmp(binaryNode, Condition.EQ);
    }

    @Override
    public boolean enterEQ_STRICT(final BinaryNode binaryNode) {
        return enterCmp(binaryNode, Condition.EQ);
    }

    @Override
    public boolean enterGE(final BinaryNode binaryNode) {
        return enterCmp(binaryNode, Condition.GE);
    }

    @Override
    public boolean enterGT(final BinaryNode binaryNode) {
        return enterCmp(binaryNode, Condition.GT);
    }

    @Override
    public boolean enterLE(final BinaryNode binaryNode) {
        return enterCmp(binaryNode, Condition.LE);
    }

    @Override
    public boolean enterLT(final BinaryNode binaryNode) {
        return enterCmp(binaryNode, Condition.LT);
    }

    @Override
    public boolean enterMOD(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.rem();
            }
        }.evaluate(binaryNode);

        return false;
    }

    @Override
    public boolean enterMUL(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.mul();
            }
        }.evaluate(binaryNode);

        return false;
    }

    @Override
    public boolean enterNE(final BinaryNode binaryNode) {
        return enterCmp(binaryNode, Condition.NE);
    }

    @Override
    public boolean enterNE_STRICT(final BinaryNode binaryNode) {
        return enterCmp(binaryNode, Condition.NE);
    }

    @Override
    public boolean enterOR(final BinaryNode binaryNode) {
        return enterAND_OR(binaryNode);
    }

    @Override
    public boolean enterSAR(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.sar();
            }
        }.evaluate(binaryNode);

        return false;
    }

    @Override
    public boolean enterSHL(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.shl();
            }
        }.evaluate(binaryNode);

        return false;
    }

    @Override
    public boolean enterSHR(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void evaluate(final BinaryNode node) {
                loadBinaryOperands(node.lhs(), node.rhs(), Type.INT);
                op();
                method.store(node.getSymbol());
            }
            @Override
            protected void op() {
                method.shr();
                method.convert(Type.LONG).load(JSType.MAX_UINT).and();
            }
        }.evaluate(binaryNode);

        return false;
    }

    @Override
    public boolean enterSUB(final BinaryNode binaryNode) {
        new BinaryArith() {
            @Override
            protected void op() {
                method.sub();
            }
        }.evaluate(binaryNode);

        return false;
    }

    @Override
    public boolean enterTernaryNode(final TernaryNode ternaryNode) {
        final Expression test      = ternaryNode.getTest();
        final Expression trueExpr  = ternaryNode.getTrueExpression();
        final Expression falseExpr = ternaryNode.getFalseExpression();

        final Symbol symbol     = ternaryNode.getSymbol();
        final Label  falseLabel = new Label("ternary_false");
        final Label  exitLabel  = new Label("ternary_exit");

        Type widest = Type.widest(ternaryNode.getType(), Type.widest(trueExpr.getType(), falseExpr.getType()));
        if (trueExpr.getType().isArray() || falseExpr.getType().isArray()) { //loadArray creates a Java array type on the stack, calls global allocate, which creates a native array type
            widest = Type.OBJECT;
        }

        load(test, Type.BOOLEAN);
        // we still keep the conversion here as the AccessSpecializer can have separated the types, e.g. var y = x ? x=55 : 17
        // will left as (Object)x=55 : (Object)17 by Lower. Then the first term can be {I}x=55 of type int, which breaks the
        // symmetry for the temporary slot for this TernaryNode. This is evidence that we assign types and explicit conversions
        // too early, or Apply the AccessSpecializer too late. We are mostly probably looking for a separate type pass to
        // do this property. Then we never need any conversions in CodeGenerator
        method.ifeq(falseLabel);
        load(trueExpr, widest);
        method._goto(exitLabel);
        method.label(falseLabel);
        load(falseExpr, widest);
        method.label(exitLabel);
        method.store(symbol);

        return false;
    }

    /**
     * Generate all shared scope calls generated during codegen.
     */
    protected void generateScopeCalls() {
        for (final SharedScopeCall scopeAccess : lc.getScopeCalls()) {
            scopeAccess.generateScopeCall();
        }
    }

    /**
     * Debug code used to print symbols
     *
     * @param block the block we are in
     * @param ident identifier for block or function where applicable
     */
    @SuppressWarnings("resource")
    private void printSymbols(final Block block, final String ident) {
        if (!compiler.getEnv()._print_symbols) {
            return;
        }

        final PrintWriter out = compiler.getEnv().getErr();
        out.println("[BLOCK in '" + ident + "']");
        if (!block.printSymbols(out)) {
            out.println("<no symbols>");
        }
        out.println();
    }


    /**
     * The difference between a store and a self modifying store is that
     * the latter may load part of the target on the stack, e.g. the base
     * of an AccessNode or the base and index of an IndexNode. These are used
     * both as target and as an extra source. Previously it was problematic
     * for self modifying stores if the target/lhs didn't belong to one
     * of three trivial categories: IdentNode, AcessNodes, IndexNodes. In that
     * case it was evaluated and tagged as "resolved", which meant at the second
     * time the lhs of this store was read (e.g. in a = a (second) + b for a += b,
     * it would be evaluated to a nop in the scope and cause stack underflow
     *
     * see NASHORN-703
     *
     * @param <T>
     */
    private abstract class SelfModifyingStore<T extends Expression> extends Store<T> {
        protected SelfModifyingStore(final T assignNode, final Expression target) {
            super(assignNode, target);
        }

        @Override
        protected boolean isSelfModifying() {
            return true;
        }
    }

    /**
     * Helper class to generate stores
     */
    private abstract class Store<T extends Expression> {

        /** An assignment node, e.g. x += y */
        protected final T assignNode;

        /** The target node to store to, e.g. x */
        private final Expression target;

        /** How deep on the stack do the arguments go if this generates an indy call */
        private int depth;

        /** If we have too many arguments, we need temporary storage, this is stored in 'quick' */
        private Symbol quick;

        /**
         * Constructor
         *
         * @param assignNode the node representing the whole assignment
         * @param target     the target node of the assignment (destination)
         */
        protected Store(final T assignNode, final Expression target) {
            this.assignNode = assignNode;
            this.target = target;
        }

        /**
         * Constructor
         *
         * @param assignNode the node representing the whole assignment
         */
        protected Store(final T assignNode) {
            this(assignNode, assignNode);
        }

        /**
         * Is this a self modifying store operation, e.g. *= or ++
         * @return true if self modifying store
         */
        protected boolean isSelfModifying() {
            return false;
        }

        private void prologue() {
            final Symbol targetSymbol = target.getSymbol();
            final Symbol scopeSymbol  = lc.getCurrentFunction().compilerConstant(SCOPE);

            /**
             * This loads the parts of the target, e.g base and index. they are kept
             * on the stack throughout the store and used at the end to execute it
             */

            target.accept(new NodeVisitor<LexicalContext>(new LexicalContext()) {
                @Override
                public boolean enterIdentNode(final IdentNode node) {
                    if (targetSymbol.isScope()) {
                        method.load(scopeSymbol);
                        depth++;
                    }
                    return false;
                }

                private void enterBaseNode() {
                    assert target instanceof BaseNode : "error - base node " + target + " must be instanceof BaseNode";
                    final BaseNode   baseNode = (BaseNode)target;
                    final Expression base     = baseNode.getBase();

                    load(base, Type.OBJECT);
                    depth += Type.OBJECT.getSlots();

                    if (isSelfModifying()) {
                        method.dup();
                    }
                }

                @Override
                public boolean enterAccessNode(final AccessNode node) {
                    enterBaseNode();
                    return false;
                }

                @Override
                public boolean enterIndexNode(final IndexNode node) {
                    enterBaseNode();

                    final Expression index = node.getIndex();
                    if (!index.getType().isNumeric()) {
                        // could be boolean here as well
                        load(index, Type.OBJECT);
                    } else {
                        load(index);
                    }
                    depth += index.getType().getSlots();

                    if (isSelfModifying()) {
                        //convert "base base index" to "base index base index"
                        method.dup(1);
                    }

                    return false;
                }

            });
        }

        private Symbol quickSymbol(final Type type) {
            return quickSymbol(type, QUICK_PREFIX.symbolName());
        }

        /**
         * Quick symbol generates an extra local variable, always using the same
         * slot, one that is available after the end of the frame.
         *
         * @param type the type of the symbol
         * @param prefix the prefix for the variable name for the symbol
         *
         * @return the quick symbol
         */
        private Symbol quickSymbol(final Type type, final String prefix) {
            final String name = lc.getCurrentFunction().uniqueName(prefix);
            final Symbol symbol = new Symbol(name, IS_TEMP | IS_INTERNAL);

            symbol.setType(type);

            symbol.setSlot(lc.quickSlot(symbol));

            return symbol;
        }

        // store the result that "lives on" after the op, e.g. "i" in i++ postfix.
        protected void storeNonDiscard() {
            if (lc.getCurrentDiscard() == assignNode) {
                assert assignNode.isAssignment();
                lc.popDiscard();
                return;
            }

            final Symbol symbol = assignNode.getSymbol();
            if (symbol.hasSlot()) {
                method.dup().store(symbol);
                return;
            }

            if (method.dup(depth) == null) {
                method.dup();
                this.quick = quickSymbol(method.peekType());
                method.store(quick);
            }
        }

        private void epilogue() {
            /**
             * Take the original target args from the stack and use them
             * together with the value to be stored to emit the store code
             *
             * The case that targetSymbol is in scope (!hasSlot) and we actually
             * need to do a conversion on non-equivalent types exists, but is
             * very rare. See for example test/script/basic/access-specializer.js
             */
            target.accept(new NodeVisitor<LexicalContext>(new LexicalContext()) {
                @Override
                protected boolean enterDefault(Node node) {
                    throw new AssertionError("Unexpected node " + node + " in store epilogue");
                }

                @Override
                public boolean enterIdentNode(final IdentNode node) {
                    final Symbol symbol = node.getSymbol();
                    assert symbol != null;
                    if (symbol.isScope()) {
                        if (isFastScope(symbol)) {
                            storeFastScopeVar(symbol, CALLSITE_SCOPE | getCallSiteFlags());
                        } else {
                            method.dynamicSet(node.getName(), CALLSITE_SCOPE | getCallSiteFlags());
                        }
                    } else {
                        method.convert(node.getType());
                        method.store(symbol);
                    }
                    return false;

                }

                @Override
                public boolean enterAccessNode(final AccessNode node) {
                    method.dynamicSet(node.getProperty().getName(), getCallSiteFlags());
                    return false;
                }

                @Override
                public boolean enterIndexNode(final IndexNode node) {
                    method.dynamicSetIndex(getCallSiteFlags());
                    return false;
                }
            });


            // whatever is on the stack now is the final answer
        }

        protected abstract void evaluate();

        void store() {
            prologue();
            evaluate(); // leaves an operation of whatever the operationType was on the stack
            storeNonDiscard();
            epilogue();
            if (quick != null) {
                method.load(quick);
            }
        }
    }

    private void newFunctionObject(final FunctionNode functionNode, final FunctionNode originalFunctionNode) {
        assert lc.peek() == functionNode;
        // We don't emit a ScriptFunction on stack for:
        // 1. the outermost compiled function (as there's no code being generated in its outer context that'd need it
        //    as a callee), and
        // 2. for functions that are immediately called upon definition and they don't need a callee, e.g. (function(){})().
        //    Such immediately-called functions are invoked using INVOKESTATIC (see enterFunctionNode() of the embedded
        //    visitor of enterCallNode() for details), and if they don't need a callee, they don't have it on their
        //    static method's parameter list.
        if (lc.getOutermostFunction() == functionNode ||
                (!functionNode.needsCallee()) && lc.isFunctionDefinedInCurrentCall(originalFunctionNode)) {
            return;
        }

        // Generate the object class and property map in case this function is ever used as constructor
        final String      className          = SCRIPTFUNCTION_IMPL_OBJECT;
        final int         fieldCount         = ObjectClassGenerator.getPaddedFieldCount(functionNode.countThisProperties());
        final String      allocatorClassName = Compiler.binaryName(ObjectClassGenerator.getClassName(fieldCount));
        final PropertyMap allocatorMap       = PropertyMap.newMap(null, 0, fieldCount, 0);

        method._new(className).dup();
        loadConstant(new RecompilableScriptFunctionData(functionNode, compiler.getCodeInstaller(), allocatorClassName, allocatorMap));

        if (functionNode.isLazy() || functionNode.needsParentScope()) {
            method.loadCompilerConstant(SCOPE);
        } else {
            method.loadNull();
        }
        method.invoke(constructorNoLookup(className, RecompilableScriptFunctionData.class, ScriptObject.class));
    }

    // calls on Global class.
    private MethodEmitter globalInstance() {
        return method.invokestatic(GLOBAL_OBJECT, "instance", "()L" + GLOBAL_OBJECT + ';');
    }

    private MethodEmitter globalObjectPrototype() {
        return method.invokestatic(GLOBAL_OBJECT, "objectPrototype", methodDescriptor(ScriptObject.class));
    }

    private MethodEmitter globalAllocateArguments() {
        return method.invokestatic(GLOBAL_OBJECT, "allocateArguments", methodDescriptor(ScriptObject.class, Object[].class, Object.class, int.class));
    }

    private MethodEmitter globalNewRegExp() {
        return method.invokestatic(GLOBAL_OBJECT, "newRegExp", methodDescriptor(Object.class, String.class, String.class));
    }

    private MethodEmitter globalRegExpCopy() {
        return method.invokestatic(GLOBAL_OBJECT, "regExpCopy", methodDescriptor(Object.class, Object.class));
    }

    private MethodEmitter globalAllocateArray(final ArrayType type) {
        //make sure the native array is treated as an array type
        return method.invokestatic(GLOBAL_OBJECT, "allocate", "(" + type.getDescriptor() + ")Ljdk/nashorn/internal/objects/NativeArray;");
    }

    private MethodEmitter globalIsEval() {
        return method.invokestatic(GLOBAL_OBJECT, "isEval", methodDescriptor(boolean.class, Object.class));
    }

    private MethodEmitter globalCheckObjectCoercible() {
        return method.invokestatic(GLOBAL_OBJECT, "checkObjectCoercible", methodDescriptor(void.class, Object.class));
    }

    private MethodEmitter globalDirectEval() {
        return method.invokestatic(GLOBAL_OBJECT, "directEval",
                methodDescriptor(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class));
    }
}
