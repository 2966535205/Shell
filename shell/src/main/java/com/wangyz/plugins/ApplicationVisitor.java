package com.wangyz.plugins;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ApplicationVisitor extends ClassVisitor implements Opcodes {

    private String mClassName;

    private String mApplicationName;

    private static final String METHOD_NAME_ATTACH_BASE_CONTEXT = "attachBaseContext";

    public ApplicationVisitor(ClassVisitor cv, String applicationName) {
        super(Opcodes.ASM5, cv);
        this.mApplicationName = applicationName;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
                      String[] interfaces) {
        mClassName = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                     String[] exceptions) {
        MethodVisitor methodVisitor = cv.visitMethod(access, name, desc, signature, exceptions);
        if (mApplicationName.equals(mClassName)) {
            if (METHOD_NAME_ATTACH_BASE_CONTEXT.equals(name)) {
                System.out.println("-------------------- ApplicationVisitor,visit method:" + name +
                        " --------------------");
                return new ApplicationAttachBaseContextVisitor(Opcodes.ASM5, methodVisitor, mApplicationName.replace(".class", ""));
            }
        }
        return methodVisitor;
    }
}