package org.kie.scanner.embedder;

import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.kie.scanner.DependencyDescriptor;
import org.kie.scanner.PomParser;

import java.util.ArrayList;
import java.util.List;

import static org.kie.scanner.embedder.MavenProjectLoader.loadMavenProject;

public class EmbeddedPomParser implements PomParser {

    private final MavenProject mavenProject;

    public EmbeddedPomParser() {
        this(loadMavenProject());
    }

    public EmbeddedPomParser(MavenProject mavenProject) {
        this.mavenProject = mavenProject;
    }

    public List<DependencyDescriptor> getPomDirectDependencies() {
        List<DependencyDescriptor> deps = new ArrayList<DependencyDescriptor>();
        for (Dependency dep : mavenProject.getDependencies()) {
            DependencyDescriptor depDescr = new DependencyDescriptor(dep);
            if (depDescr.isValid()) {
                deps.add(depDescr);
            }
        }
        return deps;
    }
}
