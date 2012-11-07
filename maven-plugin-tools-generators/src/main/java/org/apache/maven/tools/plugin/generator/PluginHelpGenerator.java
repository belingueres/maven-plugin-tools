package org.apache.maven.tools.plugin.generator;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.tools.plugin.PluginToolsRequest;
import org.apache.velocity.VelocityContext;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.PropertyUtils;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.velocity.VelocityComponent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Properties;

/**
 * Generates an <code>HelpMojo</code> class from <code>help-class-source.vm</code> template.
 * The generated mojo reads help content from <code>META-INF/maven/${groupId}/${artifactId}/plugin-help.xml</code> resource,
 * which is generated by this {@link PluginDescriptorGenerator}.
 * <p>Notice that the help mojo source needs to be generated before compilation, but when Java 5 annotations are used,
 * plugin descriptor content is available only after compilation (detecting annotations in .class files):
 * help mojo source can be generated with empty package only (and no plugin descriptor available yet), then needs
 * to be updated after compilation - through {@link #rewriteHelpMojo(PluginToolsRequest)} which is called from plugin
 * descriptor XML generation.</p>
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 * @since 2.4
 */
public class PluginHelpGenerator
    extends AbstractLogEnabled
    implements Generator
{
    /**
     * Default generated class name
     */
    private static final String HELP_MOJO_CLASS_NAME = "HelpMojo";

    /**
     * Help properties file, to store data about generated source.
     */
    private static final String HELP_PROPERTIES_FILENAME = "maven-plugin-help.properties";

    /**
     * Default goal
     */
    private static final String HELP_GOAL = "help";

    private String helpPackageName;

    private boolean useAnnotations;

    private VelocityComponent velocityComponent;

    /**
     * Default constructor
     */
    public PluginHelpGenerator()
    {
        this.enableLogging( new ConsoleLogger( Logger.LEVEL_INFO, "PluginHelpGenerator" ) );
    }

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public void execute( File destinationDirectory, PluginToolsRequest request )
        throws GeneratorException
    {
        PluginDescriptor pluginDescriptor = request.getPluginDescriptor();

        String helpImplementation = getImplementation( pluginDescriptor );

        @SuppressWarnings( "unchecked" )
        List<MojoDescriptor> mojoDescriptors = pluginDescriptor.getMojos();

        if ( mojoDescriptors != null )
        {
            // Verify that no help goal already exists
            MojoDescriptor descriptor = pluginDescriptor.getMojo( HELP_GOAL );

            if ( ( descriptor != null ) && !descriptor.getImplementation().equals( helpImplementation ) )
            {
                if ( getLogger().isWarnEnabled() )
                {
                    getLogger().warn( "\n\nA help goal (" + descriptor.getImplementation()
                                          + ") already exists in this plugin. SKIPPED THE " + helpImplementation
                                          + " GENERATION.\n" );
                }

                return;
            }
        }

        writeHelpPropertiesFile( request, destinationDirectory );
        
        useAnnotations = request.getProject().getArtifactMap().containsKey( "org.apache.maven.plugin-tools:maven-plugin-annotations" );

        try
        {
            String sourcePath = helpImplementation.replace( '.', File.separatorChar ) + ".java";

            File helpClass = new File( destinationDirectory, sourcePath );
            helpClass.getParentFile().mkdirs();

            String helpClassSources = getHelpClassSources( getPluginHelpPath( request.getProject() ), pluginDescriptor );

            FileUtils.fileWrite( helpClass, request.getEncoding(), helpClassSources );
        }
        catch ( IOException e )
        {
            throw new GeneratorException( e.getMessage(), e );
        }
    }

    public PluginHelpGenerator setHelpPackageName( String helpPackageName )
    {
        this.helpPackageName = helpPackageName;
        return this;
    }
    
    public VelocityComponent getVelocityComponent()
    {
        return velocityComponent;
    }

    public PluginHelpGenerator setVelocityComponent( VelocityComponent velocityComponent )
    {
        this.velocityComponent = velocityComponent;
        return this;
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    private String getHelpClassSources( String pluginHelpPath, PluginDescriptor pluginDescriptor )
    {
        Properties properties = new Properties();
        VelocityContext context = new VelocityContext( properties );
        if ( this.helpPackageName != null )
        {
            properties.put( "helpPackageName", this.helpPackageName );
        }
        else
        {
            properties.put( "helpPackageName", "" );
        }
        properties.put( "pluginHelpPath", pluginHelpPath );
        properties.put( "artifactId", pluginDescriptor.getArtifactId() );
        properties.put( "goalPrefix", pluginDescriptor.getGoalPrefix() );
        properties.put( "useAnnotations", useAnnotations );

        StringWriter stringWriter = new StringWriter();

        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream( "help-class-source.vm" );
        InputStreamReader isReader = null;
        try
        {
            isReader = new InputStreamReader( is, "UTF-8" ); // plugin-tools sources are UTF-8 (and even ASCII in this case)
            velocityComponent.getEngine().evaluate( context, stringWriter, "", isReader );
        }
        catch ( UnsupportedEncodingException e )
        {
            // not supposed to happen since UTF-8 is supposed to be supported by any JVM
        }
        finally
        {
            IOUtil.close( is );
            IOUtil.close( isReader );
        }

        return stringWriter.toString();
    }

    /**
     * @param pluginDescriptor The descriptor of the plugin for which to generate a help goal, must not be
     *                         <code>null</code>.
     * @return The implementation.
     */
    private String getImplementation( PluginDescriptor pluginDescriptor )
    {
        if ( StringUtils.isEmpty( helpPackageName ) )
        {
            helpPackageName = GeneratorUtils.discoverPackageName( pluginDescriptor );
        }

        return StringUtils.isEmpty( helpPackageName ) ? HELP_MOJO_CLASS_NAME : helpPackageName + '.' + HELP_MOJO_CLASS_NAME;
    }

    /**
     * Write help properties files for later use to eventually rewrite Help Mojo.
     *
     * @param request
     * @throws GeneratorException
     * @see {@link #rewriteHelpMojo(PluginToolsRequest)}
     */
    private void writeHelpPropertiesFile( PluginToolsRequest request, File destinationDirectory )
        throws GeneratorException
    {
        Properties properties = new Properties();
        properties.put( "helpPackageName", helpPackageName == null ? "" : helpPackageName );
        properties.put( "destinationDirectory", destinationDirectory.getAbsolutePath() );

        File tmpPropertiesFile =
            new File( request.getProject().getBuild().getDirectory(), HELP_PROPERTIES_FILENAME );

        if ( tmpPropertiesFile.exists() )
        {
            tmpPropertiesFile.delete();
        }
        else if ( !tmpPropertiesFile.getParentFile().exists() )
        {
            tmpPropertiesFile.getParentFile().mkdirs();
        }

        FileOutputStream fos = null;
        try
        {
            fos = new FileOutputStream( tmpPropertiesFile );
            properties.store( fos, "maven plugin help mojo generation informations" );
        }
        catch ( IOException e )
        {
            throw new GeneratorException( e.getMessage(), e );
        }
        finally
        {
            IOUtil.close( fos );
        }
    }

    static String getPluginHelpPath( MavenProject mavenProject )
    {
        return "META-INF/maven/" + mavenProject.getGroupId() + "/" + mavenProject.getArtifactId() + "/plugin-help.xml";
    }

    /**
     * Rewrite Help Mojo to match actual Mojos package name if it was not available at source generation
     * time. This is used at descriptor generation time.
     *
     * @param request
     * @throws GeneratorException
     */
    static void rewriteHelpMojo( PluginToolsRequest request, Logger log )
        throws GeneratorException
    {
        File tmpPropertiesFile =
            new File( request.getProject().getBuild().getDirectory(), HELP_PROPERTIES_FILENAME );

        if ( !tmpPropertiesFile.exists() )
        {
            return;
        }

        Properties properties = PropertyUtils.loadProperties( tmpPropertiesFile );

        String helpPackageName = properties.getProperty( "helpPackageName" );

        // if helpPackageName property is empty, we have to rewrite the class with a better package name than empty
        if ( StringUtils.isEmpty( helpPackageName ) )
        {
            String destDir = properties.getProperty( "destinationDirectory" );
            File destinationDirectory;
            if ( StringUtils.isEmpty( destDir ) )
            {
                // writeHelpPropertiesFile() creates 2 properties: find one without the other should not be possible
                log.warn( "\n\nUnexpected situation: destinationDirectory not defined in " + HELP_PROPERTIES_FILENAME
                    + " during help mojo source generation but expected during XML descriptor generation." );
                log.warn( "Please check helpmojo goal version used in previous build phase." );
                destinationDirectory = new File( "target/generated-sources/plugin" );
                log.warn( "Trying default location: " + destinationDirectory );
            }
            else
            {
                destinationDirectory = new File( destDir );
            }
            String helpMojoImplementation = rewriteHelpClassToMojoPackage( request, destinationDirectory, log );

            if ( helpMojoImplementation != null )
            {
                // rewrite plugin descriptor with new HelpMojo implementation class
                updateHelpMojoDescriptor( request.getPluginDescriptor(), helpMojoImplementation );
            }
        }
    }

    private static String rewriteHelpClassToMojoPackage( PluginToolsRequest request, File destinationDirectory, Logger log )
        throws GeneratorException
    {
        String destinationPackage = GeneratorUtils.discoverPackageName( request.getPluginDescriptor() );
        if ( StringUtils.isEmpty( destinationPackage ) )
        {
            return null;
        }
        String packageAsDirectory = StringUtils.replace( destinationPackage, '.', '/' );

        String outputDirectory = request.getProject().getBuild().getOutputDirectory();
        File helpClassFile = new File( outputDirectory, HELP_MOJO_CLASS_NAME + ".class" );
        if ( !helpClassFile.exists() )
        {
            return null;
        }

        // rewrite help mojo source
        File helpSourceFile = new File( destinationDirectory, HELP_MOJO_CLASS_NAME + ".java" );
        if ( !helpSourceFile.exists() )
        {
            log.warn( "HelpMojo.java not found in default location: " + helpSourceFile.getAbsolutePath() );
            log.warn( "Help goal source won't be moved to package: " + destinationPackage );
        }
        else
        {
            File helpSourceFileNew = new File( destinationDirectory, packageAsDirectory + '/' + HELP_MOJO_CLASS_NAME + ".java" );
            if ( !helpSourceFileNew.getParentFile().exists() )
            {
                helpSourceFileNew.getParentFile().mkdirs();
            }
            Reader sourceReader = null;
            PrintWriter sourceWriter = null;
            try
            {
                sourceReader = new InputStreamReader( new FileInputStream( helpSourceFile ), request.getEncoding() );
                sourceWriter =
                    new PrintWriter( new OutputStreamWriter( new FileOutputStream( helpSourceFileNew ),
                                                             request.getEncoding() ) );
    
                sourceWriter.println( "package " + destinationPackage + ";" );
                IOUtil.copy( sourceReader, sourceWriter );
            }
            catch ( IOException e )
            {
                throw new GeneratorException( e.getMessage(), e );
            }
            finally
            {
                IOUtil.close( sourceReader );
                IOUtil.close( sourceWriter );
            }
            helpSourceFileNew.setLastModified( helpSourceFile.lastModified() );
            helpSourceFile.delete();
        }

        // rewrite help mojo .class
        File rewriteHelpClassFile =
            new File( outputDirectory + '/' + packageAsDirectory, HELP_MOJO_CLASS_NAME + ".class" );
        if ( !rewriteHelpClassFile.getParentFile().exists() )
        {
            rewriteHelpClassFile.getParentFile().mkdirs();
        }

        FileInputStream fileInputStream = null;
        ClassReader cr = null;
        try
        {
            fileInputStream = new FileInputStream( helpClassFile );
            cr = new ClassReader( fileInputStream );
        }
        catch ( IOException e )
        {
            throw new GeneratorException( e.getMessage(), e );
        }
        finally
        {
            IOUtil.close( fileInputStream );
        }

        ClassWriter cw = new ClassWriter( 0 );

        Remapper packageRemapper =
            new SimpleRemapper( HELP_MOJO_CLASS_NAME, packageAsDirectory + '/' + HELP_MOJO_CLASS_NAME );
        ClassVisitor cv = new RemappingClassAdapter( cw, packageRemapper );

        try
        {
            cr.accept( cv, ClassReader.EXPAND_FRAMES );
        }
        catch ( Throwable e )
        {
            throw new GeneratorException( "ASM issue processing class-file " + helpClassFile.getPath(), e );
        }

        byte[] renamedClass = cw.toByteArray();
        FileOutputStream fos = null;
        try
        {
            fos = new FileOutputStream( rewriteHelpClassFile );
            fos.write( renamedClass );
        }
        catch ( IOException e )
        {
            throw new GeneratorException( "Error rewriting help class: " + e.getMessage(), e );
        }
        finally
        {
            IOUtil.close( fos );
        }

        helpClassFile.delete();

        return destinationPackage + ".HelpMojo";
    }

    private static void updateHelpMojoDescriptor( PluginDescriptor pluginDescriptor, String helpMojoImplementation )
    {
        MojoDescriptor mojoDescriptor = pluginDescriptor.getMojo( HELP_GOAL );

        if ( mojoDescriptor != null )
        {
            mojoDescriptor.setImplementation( helpMojoImplementation );
        }
    }
}
