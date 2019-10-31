package co.elastic.apm.agent;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter;
import org.apache.ivy.plugins.resolver.URLResolver;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class TestClassWithDependencyRunner {

    private WeakReference<ClassLoader> classLoader;
    @Nullable
    private BlockJUnit4ClassRunner testRunner;

    /**
     * Downloads the dependency and all its transitive dependencies via Apache Ivy.
     * Also exports a jar for the test class and all classes which reference the provided dependency.
     * All downloaded and exported jar files are then loaded from class loader with child-first semantics.
     * This avoids that the dependency will be loaded by the parent class loader which contains the {@code provided}-scoped maven dependency.
     */
    public TestClassWithDependencyRunner(String groupId, String artifactId, String version, Class<?> testClass, Class<?>... classesReferencingDependency) throws Exception {
        List<URL> urls = resolveArtifacts(groupId, artifactId, version);
        List<Class<?>> classesToExport = new ArrayList<>();
        classesToExport.add(testClass);
        classesToExport.addAll(Arrays.asList(classesReferencingDependency));
        urls.add(exportToTempJarFile(classesToExport));

        URLClassLoader testClassLoader = new ChildFirstURLClassLoader(urls);
        testRunner = new BlockJUnit4ClassRunner(testClassLoader.loadClass(testClass.getName()));
        classLoader = new WeakReference<>(testClassLoader);
    }

    public void run() {
        if (testRunner == null) {
            throw new IllegalStateException();
        }
        Result result = new JUnitCore().run(testRunner);
        for (Failure failure : result.getFailures()) {
            System.out.println(failure);
        }
        assertThat(result.wasSuccessful()).isTrue();
    }

    public void assertClassLoadersIsGCed() {
        testRunner = null;
        System.gc();
        System.gc();
        System.gc();
        assertThat(classLoader.get()).isNull();
    }

    private static URL exportToTempJarFile(List<Class<?>> classes) throws IOException {
        File tempTestJar = File.createTempFile("temp-test", ".jar");
        tempTestJar.deleteOnExit();
        try (JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(tempTestJar))) {
            for (Class<?> clazz : classes) {
                InputStream inputStream = clazz.getResourceAsStream('/' + clazz.getName().replace('.', '/') + ".class");
                try (inputStream) {
                    jarOutputStream.putNextEntry(new JarEntry(clazz.getName().replace('.', '/') + ".class"));
                    byte[] buffer = new byte[1024];
                    int index;
                    while ((index = inputStream.read(buffer)) != -1) {
                        jarOutputStream.write(buffer, 0, index);
                    }
                    jarOutputStream.closeEntry();
                }
            }
            return tempTestJar.toURI().toURL();
        }
    }
    /*
     * Taken from http://web.archive.org/web/20140420091631/http://developers-blog.org:80/blog/default/2010/11/08/Embed-Ivy-How-to-use-Ivy-with-Java
     */

    private static List<URL> resolveArtifacts(String groupId, String artifactId, String version) throws Exception {
        //creates clear ivy settings
        IvySettings ivySettings = new IvySettings();
        //url resolver for configuration of maven repo
        URLResolver resolver = new URLResolver();
        resolver.setM2compatible(true);
        resolver.setName("central");
        //you can specify the url resolution pattern strategy
        resolver.addArtifactPattern(
            "http://repo1.maven.org/maven2/"
                + "[organisation]/[module]/[revision]/[artifact](-[revision]).[ext]");
        //adding maven repo resolver
        ivySettings.addResolver(resolver);
        //set to the default resolver
        ivySettings.setDefaultResolver(resolver.getName());
        //creates an Ivy instance with settings
        Ivy ivy = Ivy.newInstance(ivySettings);

        File ivyfile = File.createTempFile("ivy", ".xml");
        ivyfile.deleteOnExit();

        DefaultModuleDescriptor md =
            DefaultModuleDescriptor.newDefaultInstance(ModuleRevisionId.newInstance(groupId,
                artifactId + "-caller", "working"));

        DefaultDependencyDescriptor dd = new DefaultDependencyDescriptor(md,
            ModuleRevisionId.newInstance(groupId, artifactId, version), false, false, true);
        md.addDependency(dd);

        //creates an ivy configuration file
        XmlModuleDescriptorWriter.write(md, ivyfile);

        String[] confs = new String[]{"default"};
        ResolveOptions resolveOptions = new ResolveOptions().setConfs(confs);

        //init resolve report
        ResolveReport report = ivy.resolve(ivyfile.toURL(), resolveOptions);

        List<URL> dependencies = new ArrayList<>();
        ArtifactDownloadReport[] allArtifactsReports = report.getAllArtifactsReports();
        for (ArtifactDownloadReport allArtifactsReport : allArtifactsReports) {
            dependencies.add(allArtifactsReport.getLocalFile().toURI().toURL());
        }

        return dependencies;
    }

    private static class ChildFirstURLClassLoader extends URLClassLoader {

        private final List<URL> urls;

        public ChildFirstURLClassLoader(List<URL> urls) {
            super(urls.toArray(new URL[]{}));
            this.urls = urls;
        }

        @Override
        public String getName() {
            return "Test class class loader: " + urls;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            try {
                Class<?> c = findClass(name);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return super.getResources(name);
        }

        @Override
        public String toString() {
            return getName();
        }

    }
}
