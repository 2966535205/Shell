package com.wangyz.plugins


import com.android.build.api.transform.*
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.utils.FileUtils
import com.wangyz.plugins.util.FileUtil
import com.wangyz.plugins.util.ShellUtil
import com.wangyz.plugins.util.ZipUtil
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.compress.utils.IOUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class ShellPlugin extends Transform implements Plugin<Project> {

    private Project mProject

    private String mShellName

    public String mShellApplication

    private String mShellApplicationDir

    private String mShellApplicationClass

    private String mOutput

    private boolean mGenerateClass

    private printLog(Object msg) {
        println("******************************")
        println(msg)
        println("******************************\n")
    }

    @Override
    void apply(Project project) {

        printLog('ShellPlugin apply')

        mProject = project

        project.extensions.create("shellConfig", ShellConfig)

        def android = project.extensions.getByType(AppExtension)
        android.registerTransform(this)

        project.afterEvaluate {
            project.tasks.matching {
                it.name == 'assembleRelease'
            }.each {
                task ->
                    printLog(task.name)

                    mShellName = mProject.shellConfig.shellModuleName

                    mShellApplication = mProject.shellConfig.shellApplication

                    printLog(mShellApplication)

                    mShellApplicationDir = mShellApplication.substring(0, mShellApplication.lastIndexOf(".")).replace(".", "/")

                    printLog(mShellApplicationDir)

                    mShellApplicationClass = mShellApplication.replace(".", "/") + ".class"

                    printLog(mShellApplicationClass)

                    def shellProject = project.parent.findProject(mShellName)
                    printLog("shellProject:$shellProject")

                    mOutput = "$mShellName-release"

                    File shellDir = new File("${mProject.rootDir}/$mOutput")

                    File apkFile

                    File aarFile = new File("${shellProject.buildDir}/outputs/aar/$mShellName-release.aar")

                    printLog(aarFile.getAbsolutePath())

                    project.android.applicationVariants.all {
                        variant ->
                            variant.outputs.each {
                                output ->
                                    def outputFile = output.outputFile
                                    printLog("outputFile:${outputFile.getAbsolutePath()}")
                                    if (outputFile.name.contains("release")) {
                                        apkFile = outputFile
                                    }
                            }
                    }

                    task.doFirst {
                        //生成aar
                        printLog("begin generate aar")
                        project.exec {
                            workingDir("../$mShellName/")
                            commandLine('cmd', '/c', 'gradle', 'assembleRelease')
                        }
                        printLog("generate aar complete")

                        //复制文件
                        printLog("begin copy aar")
                        project.copy {
                            from aarFile
                            into shellDir
                        }
                        printLog("copy aar complete")

                        //替换aar中的class.jar
                        File aarUnzipDir = new File("${mProject.rootDir}/$mOutput/outputs/aar/")
                        if (!aarUnzipDir.exists()) {
                            aarUnzipDir.mkdirs()
                        }
                        ZipUtil.unZip(aarFile, aarUnzipDir)

                        ZipUtil.unZip(new File("${mProject.rootDir}/$mOutput/outputs/aar/classes.jar"), new File("${mProject.rootDir}/$mOutput/outputs/aar/classes/"))

                        project.copy {
                            from "${mProject.rootDir}/$mOutput/classes/"
                            into "${mProject.rootDir}/$mOutput/outputs/aar/classes/"
                        }

                        ZipUtil.zip(new File("${mProject.rootDir}/$mOutput/outputs/aar/classes/"), new File("${mProject.rootDir}/$mOutput/outputs/aar/classes.jar"))

                        System.sleep(1000)

                        FileUtil.delete("${mProject.rootDir}/$mOutput/outputs/aar/classes/")

                        ZipUtil.zip(new File("${mProject.rootDir}/$mOutput/outputs/aar/"), new File("${mProject.rootDir}/$mOutput/$mShellName-release.aar"))

                        FileUtil.delete(new File("${mProject.rootDir}/$mOutput/outputs/aar"))

                        FileUtil.delete("${mProject.rootDir}/$mOutput/classes/")

                    }

                    task.doLast {
                        printLog("begin copy apk")
                        //复制文件
                        project.copy {
                            from apkFile
                            into shellDir
                        }
                        printLog("copy ${apkFile.name} complete")

                        printLog("begin shell")

                        ShellUtil.shell(new File("${mProject.rootDir}/$mOutput/${apkFile.name}").getAbsolutePath(), new File("${mProject.rootDir}/$mOutput/$mShellName-release.aar").getAbsolutePath(), shellDir.getAbsolutePath(), project.shellConfig.keyStore, project.shellConfig.keyStorePassword, project.shellConfig.keyPassword, project.shellConfig.alias)

                        printLog("end shell")
                    }
            }
        }
    }

    @Override
    String getName() {
        return "ShellPlugin"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        println('--------------------ShellPlugin transform start--------------------')
        def startTime = System.currentTimeMillis()
        Collection<TransformInput> inputs = transformInvocation.inputs
        TransformOutputProvider outputProvider = transformInvocation.outputProvider
        //删除旧的输出
        if (outputProvider != null) {
            outputProvider.deleteAll()
        }
        //遍历inputs
        inputs.each { input ->
            //遍历directoryInputs
            input.directoryInputs.each {
                directoryInput -> handleDirectoryInput(directoryInput, outputProvider)
            }
            //遍历jarInputs
            input.jarInputs.each {
                jarInput -> handleJarInput(jarInput, outputProvider)
            }
        }
        def time = (System.currentTimeMillis() - startTime) / 1000
        println('-------------------- ShellPlugin transform end --------------------')
        println("ShellPlugin cost $time s")
    }

    /**
     * 处理目录下的class文件
     * @param directoryInput
     * @param outputProvider
     */
    void handleDirectoryInput(DirectoryInput directoryInput, TransformOutputProvider outputProvider) {
        //是否为目录
        if (directoryInput.file.isDirectory()) {
            //列出目录所有文件（包含子文件夹，子文件夹内文件）
            directoryInput.file.eachFileRecurse {
                file ->
                    def name = file.name
                    if (isClassFile(name)) {
                        println("-------------------- handle class file:<$name> --------------------")
                        ClassReader classReader = new ClassReader(file.bytes)
                        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
                        ClassVisitor classVisitor = new ApplicationVisitor(classWriter, mShellApplication.replace(".", "/"))
                        classReader.accept(classVisitor, org.objectweb.asm.ClassReader.EXPAND_FRAMES)
                        ShellUtil.unShell(classWriter,mShellApplicationClass.replace(".class",""))
                        byte[] bytes = classWriter.toByteArray()
                        FileOutputStream fileOutputStream = new FileOutputStream(file.parentFile.absolutePath + File.separator + name)
                        fileOutputStream.write(bytes)
                        fileOutputStream.close()

                        File classDir = new File("${mProject.rootDir}/$mOutput/classes/$mShellApplicationDir")
                        if (!classDir.exists()) {
                            classDir.mkdirs()
                        }

                        //复制一份class,后面壳aar要用到
                        FileOutputStream fosShellApplication = new FileOutputStream("${mProject.rootDir}/$mOutput/classes/$mShellApplicationClass")
                        fosShellApplication.write(bytes)
                        fosShellApplication.flush()
                    }
            }
        }
        def dest = outputProvider.getContentLocation(directoryInput.name, directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)
        FileUtils.copyDirectory(directoryInput.file, dest)
    }

    /**
     * 处理Jar中的class文件
     * @param jarInput
     * @param outputProvider
     */
    void handleJarInput(JarInput jarInput, TransformOutputProvider outputProvider) {
        if (jarInput.file.getAbsolutePath().endsWith(".jar")) {
            //重名名输出文件,因为可能同名,会覆盖
            def jarName = jarInput.name
            def md5Name = DigestUtils.md5Hex(jarInput.file.absolutePath)
            if (jarName.endsWith(".jar")) {
                jarName = jarName.substring(0, jarName.length() - 4)
            }
            JarFile jarFile = new JarFile(jarInput.file)
            Enumeration enumeration = jarFile.entries()
            File tempFile = new File(jarInput.file.parent + File.separator + "temp.jar")
            //避免上次的缓存被重复插入
            if (tempFile.exists()) {
                tempFile.delete()
            }
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(tempFile))
            //保存
            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = enumeration.nextElement()
                String entryName = jarEntry.name
                ZipEntry zipEntry = new ZipEntry(entryName)
                InputStream inputStream = jarFile.getInputStream(zipEntry)
                if (isClassFile(entryName)) {
                    println("-------------------- handle jar file:<$entryName> --------------------")
                    jarOutputStream.putNextEntry(zipEntry)
                    ClassReader classReader = new ClassReader(IOUtils.toByteArray(inputStream))
                    ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
                    ClassVisitor classVisitor = new ApplicationVisitor(classWriter, mShellApplication.replace(".", "/"))
                    classReader.accept(classVisitor, org.objectweb.asm.ClassReader.EXPAND_FRAMES)
                    ShellUtil.unShell(classWriter,mShellApplicationClass.replace(".class",""))
                    byte[] bytes = classWriter.toByteArray()
                    jarOutputStream.write(bytes)

                    File appDir = new File("${mProject.rootDir}/$mOutput/classes/$mShellApplicationDir")
                    if (!appDir.exists()) {
                        appDir.mkdirs()
                    }
                    //复制一份class,后面壳aar要用到
                    FileOutputStream fosShellApplication = new FileOutputStream("${mProject.rootDir}/$mOutput/classes/$mShellApplicationClass")
                    fosShellApplication.write(bytes)
                    fosShellApplication.flush()
                } else {
                    jarOutputStream.putNextEntry(zipEntry)
                    jarOutputStream.write(IOUtils.toByteArray(inputStream))
                }
                jarOutputStream.closeEntry()
            }

            if (!mGenerateClass) {
                //生成依赖的class
                byte[] zipUtilByte = ShellUtil.generateZipUtils()
                jarOutputStream.putNextEntry(new ZipEntry("com/wangyz/plugins/util/ZipUtils.class"))
                jarOutputStream.write(zipUtilByte)

                byte[] encryptUtilByte = ShellUtil.generateEncryptUtils()
                jarOutputStream.putNextEntry(new ZipEntry("com/wangyz/plugins/util/EncryptUtils.class"))
                jarOutputStream.write(encryptUtilByte)

                byte[] classLoaderUtilByte = ShellUtil.generateClassLoaderUtils()
                jarOutputStream.putNextEntry(new ZipEntry("com/wangyz/plugins/util/ClassLoaderUtils.class"))
                jarOutputStream.write(classLoaderUtilByte)

                //删除原来的文件夹
                File shellDir = new File("${mProject.rootDir}/$mOutput")
                if (shellDir.exists()) {
                    shellDir.deleteDir()
                }
                //生成文件夹
                shellDir.mkdirs()

                //生成class的文件夹
                File classDir = new File("${mProject.rootDir}/$mOutput/classes/com/wangyz/plugins/util/")
                classDir.mkdirs()

                FileOutputStream fosZipUtil = new FileOutputStream("${mProject.rootDir}/$mOutput/classes/com/wangyz/plugins/util/ZipUtils.class")
                fosZipUtil.write(zipUtilByte)
                fosZipUtil.flush()

                FileOutputStream fosEncryptUtil = new FileOutputStream("${mProject.rootDir}/$mOutput/classes/com/wangyz/plugins/util/EncryptUtils.class")
                fosEncryptUtil.write(encryptUtilByte)
                fosEncryptUtil.flush()

                FileOutputStream fosClassLoaderUtil = new FileOutputStream("${mProject.rootDir}/$mOutput/classes/com/wangyz/plugins/util/ClassLoaderUtils.class")
                fosClassLoaderUtil.write(classLoaderUtilByte)
                fosClassLoaderUtil.flush()

                mGenerateClass = true
            }

            jarOutputStream.close()

            jarFile.close()
            def dest = outputProvider.getContentLocation(jarName + "_" + md5Name, jarInput.contentTypes, jarInput.scopes, Format.JAR)
            FileUtils.copyFile(tempFile, dest)
            tempFile.delete()
        }
    }

    /**
     * 判断是否为需要处理class文件
     * @param name
     * @return
     */
    boolean isClassFile(String name) {
        return (name.endsWith(".class") && !name.startsWith("R\$")
                && "R.class" != name && "BuildConfig.class" != name && name == mShellApplicationClass)
    }
}