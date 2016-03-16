package de.ii.gradle.plugins.osgi

import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.plugins.osgi.OsgiPlugin
import org.gradle.api.InvalidUserDataException
import org.apache.felix.ipojo.manipulator.Pojoization
import org.apache.felix.ipojo.manipulator.reporter.EmptyReporter
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.zip.ZipEntry;
import java.net.URL;
import java.net.URLClassLoader;

class IpojoPlugin implements Plugin<Project> {

    void apply(Project project) {
        project.plugins.apply(OsgiPlugin.class);
        
        project.tasks.jar.doFirst {
            def embedInstruction = project.jar.manifest.instructions.get("Embed-Dependency")
            def transitive = project.jar.manifest.instructions.get("Embed-Transitive") != null && project.jar.manifest.instructions.get("Embed-Transitive")[0] == "true";
            def export = project.jar.manifest.instructions.get("Embed-Export") != null && project.jar.manifest.instructions.get("Embed-Export")[0] == "true";
            def doimport = project.jar.manifest.instructions.get("Embed-Import") != null && project.jar.manifest.instructions.get("Embed-Import")[0] == "true";
            
            if(embedInstruction != null){
                def includedArtifacts = [] as Set

                def deps = getDependencies(project, embedInstruction, transitive)

                deps.each { dependency ->
                    dependency.moduleArtifacts.each { artifact ->
                        includedArtifacts.add(artifact.file)
                    }
                }

                    project.jar.manifest.instruction("Bundle-ClassPath",'.') //add the default classpath
                    includedArtifacts.each { artifact -> 
                        project.jar.from(artifact)
                        project.jar.manifest.instruction("Bundle-ClassPath",artifact.name)
                        // for bnd analysis
                        if (doimport) {
                            project.copy {
                                from artifact
                                into project.sourceSets.main.output.classesDir
                            }
                        }
                    }

                    def pkgs = getPackages(deps)

                    if (export) {
                        // export only direct dependencies
                        // pkgs = getPackages(getDependencies(project, embedInstruction, false))
                        pkgs.each { pkg ->
                            project.jar.manifest.instruction("Export-Package", "${pkg.name};version=${pkg.version}")
                            project.jar.manifest.instruction("Import-Package", "${pkg.name}")
                        }

                        project.jar.manifest.instruction("Export-Package", "*")
                    }
                    else {
                        pkgs.each { pkg ->
                            project.jar.manifest.instructionFirst("Import-Package", "!${pkg.name}")
                        }
                    }

                    project.jar.manifest.instruction("Import-Package", "*")

                    
                }
            }

            project.tasks.jar << { 

                Pojoization pojo = new Pojoization(new EmptyReporter())

                File jarfile = project.file(project.jar.archivePath)
                File targetJarFile = project.file(project.jar.destinationDir.absolutePath +"/" + project.jar.baseName + "_out.jar")

                if (!jarfile.exists()) throw new InvalidUserDataException("The specified bundle file does not exist: " + jarfile.absolutePath)

                def classLoaderUrls = [new URL("file://${jarfile.absolutePath}")];

                def dependencies = [] as Set
                project.configurations.runtime.resolvedConfiguration.firstLevelModuleDependencies.each { dependency -> 
                    dependencies.addAll(getDependenciesRecursive(dependency, true))
                }
                dependencies.each { dependency ->
                    dependency.moduleArtifacts.each { art ->
                        classLoaderUrls << new URL("file://${art.file.absolutePath}")
                    }
                }

                URLClassLoader urlClassLoader = new URLClassLoader(classLoaderUrls as URL[]);

                pojo.pojoization(jarfile, targetJarFile, (File) null, urlClassLoader)

                pojo.getWarnings().each { s ->  
                    println s
                }   

                if (jarfile.delete()) {
                    if ( !targetJarFile.renameTo(jarfile) ) { 
                        throw new InvalidUserDataException("Cannot rename the manipulated jar file");
                    }   
                    }else { 
                        throw new InvalidUserDataException("Cannot delete the input jar file")
                    }   
                }
            }

    /**
     * Gets the list of ResolvedDependencies for the list of embeded dependency names
     * @param embededList the list with the dependencies to embed
     * @param recursive The embed transitive state
     * @return the list of dependencies. An empty Set if none
     */
     def getDependencies(project, embededList, recursive){
        def dependencies = [] as Set //resolved Dependencies
        def dependencyMap = [:]; 
        // This only considers top level resolved dependencies, but other should 
        // not be embeded anyway.
        project.configurations.runtime.resolvedConfiguration.firstLevelModuleDependencies.each { dependency -> 
            dependencyMap.put(dependency.moduleName,dependency)
        }
        embededList.each { embeded -> 
            def dependency = dependencyMap.get(embeded)
            if(dependency != null){
                dependencies.addAll(getDependenciesRecursive(dependency, recursive))
                } else {
                    println "WARNING: dependency "+embeded+" not found"
                }
            }
            return dependencies
        }

        def getDependenciesRecursive(dependency, recursive) {
            def dependencies = [] as Set
            //println "dependency "+dependency.name
            if(recursive){
                dependency.children.each { child -> 
                        //println "  child "+child.name+" Parents: "+child.parents
                        dependencies.addAll(getDependenciesRecursive(child, recursive))
                    }
                }
                dependencies.add(dependency)

                return dependencies
            }

            def getPackages(dependencies) {
                def packages = [] as Set

                dependencies.each { dep ->
                    dep.moduleArtifacts.each { art ->
                    //println " - artifact " + art.file.absolutePath
                    
                    // Your jar file
                    JarFile jar = new JarFile(art.file);
                    // Getting the files into the jar
                    Enumeration<? extends JarEntry> enumeration = jar.entries();

                    // Iterates into the files in the jar file
                    while (enumeration.hasMoreElements()) {
                        ZipEntry zipEntry = enumeration.nextElement();

                        // Is this a class?
                        if (zipEntry.getName().endsWith(".class")) {
                            packages.add([name: zipEntry.getName().substring(0, zipEntry.getName().lastIndexOf('/')).replace('/','.'), version: dep.moduleVersion])
                        }
                    }
                }
            }
            /*packages.each { pkg ->
                println "package " + pkg
                }*/

                return packages
            }
        }
