/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2019 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.detect;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;
import org.apache.bcel.Const;

import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.OpcodeUtils;
import com.mebigfatguy.fbcontrib.utils.RegisterUtils;
import com.mebigfatguy.fbcontrib.utils.SignatureBuilder;
import com.mebigfatguy.fbcontrib.utils.TernaryPatcher;
import com.mebigfatguy.fbcontrib.utils.ToString;
import com.mebigfatguy.fbcontrib.utils.Values;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.ba.XField;

/**
 * looks for loops that transfers the contents of one collection to another. These collection sources might be local variables or member fields, including sets,
 * maps key/values, lists, or arrays. It is simpler to just use the addAll method of the collection class. In the case where the source is an array, you can use
 * Arrays.asList(array), and use that as the source to addAll.
 */
@CustomUserValue
public class UseAddAll extends AbstractCollectionScanningDetector {

    /** register/field to alias register/field */
    private Map<Comparable<?>, Comparable<?>> userValues;
    /** alias register to loop info */
    private Map<Comparable<?>, LoopInfo> loops;
    private boolean isInstanceMethod;

    /**
     * constructs a UAA detector given the reporter to report bugs on
     *
     * @param bugReporter
     *            the sync of bug reports
     */
    public UseAddAll(BugReporter bugReporter) {
        super(bugReporter, Values.SLASHED_JAVA_UTIL_COLLECTION);
    }

    /**
     * implements the visitor to reset the userValues and loops
     *
     * @param obj
     *            the context object of the currently parsed code block
     */
    @Override
    public void visitCode(Code obj) {
        try {
            userValues = new HashMap<>();
            loops = new HashMap<>();
            isInstanceMethod = !getMethod().isStatic();
            super.visitCode(obj);
        } finally {
            userValues = null;
            loops = null;
        }
    }

    /**
     * implements the visitor to look for manually copying of collections to collections
     *
     * @param seen
     *            the opcode of the currently parsed instruction
     */
    @Override
    public void sawOpcode(int seen) {
        Comparable<?> regOrField = null;
        Comparable<?> uValue;
        boolean sawAlias = false;
        boolean sawLoad = false;

        try {
            stack.precomputation(this);

            int pc = getPC();
            Iterator<LoopInfo> it = loops.values().iterator();
            while (it.hasNext()) {
                LoopInfo loop = it.next();
                int endPC = loop.getEndPC();
                int loopPC = loop.getAddPC();
                if ((endPC - 3) <= pc) {
                    if (loopPC > 0) {
                        bugReporter.reportBug(new BugInstance(this, BugType.UAA_USE_ADD_ALL.name(), NORMAL_PRIORITY).addClass(this).addMethod(this)
                                .addSourceLine(this, loopPC));
                    }
                    it.remove();
                } else if ((endPC > pc) && (loopPC < (pc - 5)) && (loopPC > 0)) {
                    it.remove();
                }
            }

            if (seen == Const.INVOKEINTERFACE) {
                String methodName = getNameConstantOperand();
                String signature = getSigConstantOperand();
                if ("get".equals(methodName) && SignatureBuilder.SIG_INT_TO_OBJECT.equals(signature)) {
                    if (stack.getStackDepth() > 1) {
                        OpcodeStack.Item itm = stack.getStackItem(1);
                        int reg = isLocalCollection(itm);
                        if (reg >= 0) {
                            regOrField = Integer.valueOf(reg);
                            sawAlias = true;
                        } else {
                            String field = isFieldCollection(itm);
                            if (field != null) {
                                regOrField = field;
                                sawAlias = true;
                            }
                        }
                    }
                } else if ("keySet".equals(methodName) || "values".equals(methodName) || "iterator".equals(methodName) || "next".equals(methodName)
                        || "hasNext".equals(methodName)) {
                    if (stack.getStackDepth() > 0) {
                        OpcodeStack.Item itm = stack.getStackItem(0);
                        int reg = isLocalCollection(itm);
                        if (reg >= 0) {
                            regOrField = Integer.valueOf(reg);
                            sawAlias = true;
                        } else {
                            String field = isFieldCollection(itm);
                            if (field != null) {
                                regOrField = field;
                                sawAlias = true;
                            }
                        }
                    }
                } else if ("add".equals(methodName) && SignatureBuilder.SIG_OBJECT_TO_BOOLEAN.equals(signature) && (stack.getStackDepth() > 1)) {
                    OpcodeStack.Item colItem = stack.getStackItem(1);
                    OpcodeStack.Item valueItem = stack.getStackItem(0);
                    int reg = isLocalCollection(colItem);
                    if (reg >= 0) {
                        regOrField = Integer.valueOf(reg);
                        uValue = (Comparable<?>) valueItem.getUserValue();
                        if (uValue != null) {
                            LoopInfo loop = loops.get(uValue);
                            if ((loop != null) && loop.isInLoop(pc) && (this.getCodeByte(getNextPC()) == Const.POP)) {
                                loop.foundAdd(pc);
                            }
                        }
                    } else {
                        String field = isFieldCollection(colItem);
                        if (field != null) {
                            regOrField = field;
                            uValue = (Comparable<?>) valueItem.getUserValue();
                            if (uValue != null) {
                                LoopInfo loop = loops.get(uValue);
                                if ((loop != null) && loop.isInLoop(pc) && (this.getCodeByte(getNextPC()) == Const.POP)) {
                                    loop.foundAdd(pc);
                                }
                            }
                        }
                    }
                }
            } else if (OpcodeUtils.isIStore(seen) || OpcodeUtils.isAStore(seen)) {
                if (stack.getStackDepth() > 0) {
                    uValue = (Comparable<?>) stack.getStackItem(0).getUserValue();
                    userValues.put(Integer.valueOf(RegisterUtils.getStoreReg(this, seen)), uValue);
                }
            } else if (OpcodeUtils.isILoad(seen) || OpcodeUtils.isALoad(seen)) {
                sawLoad = true;
            } else if (seen == Const.IFEQ) {
                boolean loopFound = false;
                if ((stack.getStackDepth() > 0) && (getBranchOffset() > 0)) {
                    int gotoPos = getBranchTarget() - 3;
                    byte[] code = getCode().getCode();
                    if ((0x00FF & code[gotoPos]) == Const.GOTO) {
                        short brOffset = (short) (0x0FF & code[gotoPos + 1]);
                        brOffset <<= 8;
                        brOffset |= (0x0FF & code[gotoPos + 2]);
                        gotoPos += brOffset;
                        if (gotoPos < pc) {
                            OpcodeStack.Item itm = stack.getStackItem(0);
                            uValue = (Comparable<?>) itm.getUserValue();
                            if (uValue != null) {
                                loops.put(uValue, new LoopInfo(pc, getBranchTarget()));
                            }
                            loopFound = true;
                        }
                    }

                    if (!loopFound) {
                        removeLoop(pc);
                    }
                }
            } else if (isInstanceMethod && (seen == Const.PUTFIELD)) {
                if (stack.getStackDepth() > 1) {
                    OpcodeStack.Item item = stack.getStackItem(1);
                    if (item.getRegisterNumber() == 0) {
                        uValue = (Comparable<?>) stack.getStackItem(0).getUserValue();
                        userValues.put(getNameConstantOperand(), uValue);
                    }
                }
            } else if (isInstanceMethod && (seen == Const.GETFIELD)) {
                if (stack.getStackDepth() > 0) {
                    OpcodeStack.Item item = stack.getStackItem(0);
                    if (item.getRegisterNumber() == 0) {
                        sawLoad = true;
                    }
                }
            } else if (((seen > Const.IFEQ) && (seen <= Const.GOTO)) || (seen == Const.IFNULL) || (seen == Const.IFNONNULL)) {
                removeLoop(pc);
            } else if ((seen == Const.CHECKCAST) && (stack.getStackDepth() > 0)) {
                OpcodeStack.Item itm = stack.getStackItem(0);
                uValue = (Comparable<?>) itm.getUserValue();
                if (uValue != null) {
                    regOrField = uValue;
                    sawAlias = true;
                }
            }
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
        } finally {
            TernaryPatcher.pre(stack, seen);
            stack.sawOpcode(this, seen);
            TernaryPatcher.post(stack, seen);
            if (sawAlias) {
                if (stack.getStackDepth() > 0) {
                    OpcodeStack.Item itm = stack.getStackItem(0);
                    itm.setUserValue(regOrField);
                }
            } else if (sawLoad && (stack.getStackDepth() > 0)) {
                OpcodeStack.Item itm = stack.getStackItem(0);
                int reg = itm.getRegisterNumber();
                if (reg >= 0) {
                    uValue = userValues.get(Integer.valueOf(reg));
                    itm.setUserValue(uValue);
                } else {
                    XField xField = itm.getXField();
                    if (xField != null) {
                        uValue = userValues.get(xField.getName());
                        itm.setUserValue(uValue);
                    }
                }
            }
        }
    }

    /**
     * determines if the stack item refers to a collection that is stored in a field
     *
     * @param item
     *            the stack item to check
     *
     * @return the field name of the collection, or null
     * @throws ClassNotFoundException
     *             if the items class cannot be found
     */
    @Nullable
    private String isFieldCollection(OpcodeStack.Item item) throws ClassNotFoundException {
        Comparable<?> aliasReg = (Comparable<?>) item.getUserValue();
        if (aliasReg instanceof String) {
            return (String) aliasReg;
        }

        XField field = item.getXField();
        if (field == null) {
            return null;
        }

        JavaClass cls = item.getJavaClass();
        if ((cls != null) && cls.implementationOf(collectionClass)) {
            return field.getName();
        }

        return null;
    }

    private void removeLoop(int pc) {
        Iterator<LoopInfo> it = loops.values().iterator();
        while (it.hasNext()) {
            if (it.next().isInLoop(pc)) {
                it.remove();
            }
        }
    }

    /**
     * represents a loop, and where an add was found in it
     */
    static class LoopInfo {
        private final int start;
        private final int end;
        private int addPC;

        LoopInfo(int loopStart, int loopEnd) {
            start = loopStart;
            end = loopEnd;
            addPC = 0;
        }

        boolean isInLoop(int pc) {
            return ((pc >= start) && (pc <= end));
        }

        void foundAdd(int pc) {
            if (addPC == 0) {
                addPC = pc;
            } else {
                addPC = -1;
            }
        }

        int getStartPC() {
            return start;
        }

        int getEndPC() {
            return end;
        }

        int getAddPC() {
            return addPC;
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }
    }
}
