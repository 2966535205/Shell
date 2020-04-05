package com.wangyz.plugins;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;

/**
 * @author wangyz
 */
public class ApplicationAttachBaseContextVisitor extends MethodVisitor {

    private String mClassName;

    public ApplicationAttachBaseContextVisitor(int api, MethodVisitor mv, String className) {
        super(api, mv);
        this.mClassName = className;
    }

    @Override
    public void visitCode() {
        super.visitCode();
    }

    @Override
    public void visitInsn(int opcode) {
        if (opcode == Opcodes.RETURN) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, mClassName, "unShell", "()V", false);
        }
        super.visitInsn(opcode);
    }
}
