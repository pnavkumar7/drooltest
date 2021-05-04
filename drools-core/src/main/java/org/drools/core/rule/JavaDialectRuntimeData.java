/*
 * Copyright 2005 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.core.rule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import org.drools.core.common.ProjectClassLoader;
import org.drools.core.definitions.impl.KnowledgePackageImpl;
import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.spi.Constraint;
import org.drools.core.spi.Wireable;
import org.drools.core.util.ClassUtils;
import org.drools.core.util.KeyStoreHelper;
import org.drools.core.util.StringUtils;
import org.kie.internal.concurrent.ExecutorProviderFactory;
import org.kie.internal.utils.FastClassLoader;

import static org.drools.core.util.ClassUtils.convertClassToResourcePath;
import static org.drools.core.util.ClassUtils.convertResourceToClassName;

public class JavaDialectRuntimeData
                                   implements
                                   DialectRuntimeData,
                                   Externalizable {

    private static final long              serialVersionUID = 510l;

    private static final ProtectionDomain  PROTECTION_DOMAIN;

    private final Map<String, Wireable>    invokerLookups = new ConcurrentHashMap<>();

    private final Map<String, byte[]>      classLookups = new ConcurrentHashMap<>();

    private Map<String, byte[]>            store = new HashMap<String, byte[]>();

    private transient ClassLoader          classLoader;

    private transient ClassLoader          rootClassLoader;

    private boolean                        dirty;

    private List<String>                   wireList         = Collections.<String> emptyList();

    static {
        PROTECTION_DOMAIN = (ProtectionDomain) AccessController.doPrivileged( new PrivilegedAction() {

            public Object run() {
                return JavaDialectRuntimeData.class.getProtectionDomain();
            }
        } );
    }

    public JavaDialectRuntimeData() {
        this.dirty = false;
    }

    /**
     * Handles the write serialization of the PackageCompilationData. Patterns in Rules may reference generated data which cannot be serialized by
     * default methods. The PackageCompilationData holds a reference to the generated bytecode. The generated bytecode must be restored before any Rules.
     */
    public void writeExternal( ObjectOutput stream ) throws IOException {
        KeyStoreHelper helper = new KeyStoreHelper();

        stream.writeBoolean( helper.isSigned() );
        if (helper.isSigned()) {
            stream.writeObject( helper.getPvtKeyAlias() );
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = new ObjectOutputStream( bos );

        out.writeInt( this.store.size() );
        for (Entry<String, byte[]> entry : this.store.entrySet()) {
            out.writeObject(entry.getKey());
            out.writeObject(entry.getValue());
        }
        out.flush();
        out.close();
        byte[] buff = bos.toByteArray();
        stream.writeObject( buff );
        if (helper.isSigned()) {
            sign( stream,
                  helper,
                  buff );
        }

        stream.writeInt( this.invokerLookups.size() );
        for (Entry<String, Wireable> entry : this.invokerLookups.entrySet()) {
            stream.writeObject(entry.getKey());
            stream.writeObject(entry.getValue());
        }

        stream.writeInt( this.classLookups.size() );
        for (Entry<String, byte[]> entry : this.classLookups.entrySet()) {
            stream.writeObject( entry.getKey() );
            stream.writeObject( entry.getValue() );
        }

    }

    private void sign( final ObjectOutput stream,
            KeyStoreHelper helper,
            byte[] buff ) {
        try {
            stream.writeObject( helper.signDataWithPrivateKey( buff ) );
        } catch (Exception e) {
            throw new RuntimeException( "Error signing object store: " + e.getMessage(),
                                        e );
        }
    }

    /**
     * Handles the read serialization of the PackageCompilationData. Patterns in Rules may reference generated data which cannot be serialized by
     * default methods. The PackageCompilationData holds a reference to the generated bytecode; which must be restored before any Rules.
     * A custom ObjectInputStream, able to resolve classes against the bytecode, is used to restore the Rules.
     */
    public void readExternal( ObjectInput stream ) throws IOException,
            ClassNotFoundException {
        KeyStoreHelper helper = new KeyStoreHelper();
        boolean signed = stream.readBoolean();
        if (helper.isSigned() != signed) {
            throw new RuntimeException( "This environment is configured to work with " +
                                        ( helper.isSigned() ? "signed" : "unsigned" ) +
                                        " serialized objects, but the given object is " +
                                        ( signed ? "signed" : "unsigned" ) + ". Deserialization aborted." );
        }
        String pubKeyAlias = null;
        if (signed) {
            pubKeyAlias = (String) stream.readObject();
            if (helper.getPubKeyStore() == null) {
                throw new RuntimeException( "The package was serialized with a signature. Please configure a public keystore with the public key to check the signature. Deserialization aborted." );
            }
        }

        // Return the object stored as a byte[]
        byte[] bytes = (byte[]) stream.readObject();
        if (signed) {
            checkSignature( stream,
                            helper,
                            bytes,
                            pubKeyAlias );
        }

        ObjectInputStream in = new ObjectInputStream( new ByteArrayInputStream( bytes ) );
        for (int i = 0, length = in.readInt(); i < length; i++) {
            this.store.put( (String) in.readObject(),
                            (byte[]) in.readObject() );
        }
        in.close();

        for (int i = 0, length = stream.readInt(); i < length; i++) {
            this.invokerLookups.put( (String) stream.readObject(),
                                     (Wireable) stream.readObject() );
        }

        for (int i = 0, length = stream.readInt(); i < length; i++) {
            this.classLookups.put( (String) stream.readObject(),
                                   (byte[]) stream.readObject() );
        }

        // mark it as dirty, so that it reloads everything.
        this.dirty = true;
    }

    private void checkSignature( final ObjectInput stream,
            final KeyStoreHelper helper,
            final byte[] bytes,
            final String pubKeyAlias ) throws ClassNotFoundException,
            IOException {
        byte[] signature = (byte[]) stream.readObject();
        try {
            if (!helper.checkDataWithPublicKey( pubKeyAlias,
                                                bytes,
                                                signature )) {
                throw new RuntimeException( "Signature does not match serialized package. This is a security violation. Deserialisation aborted." );
            }
        } catch (InvalidKeyException e) {
            throw new RuntimeException( "Invalid key checking signature: " + e.getMessage(),
                                        e );
        } catch (KeyStoreException e) {
            throw new RuntimeException( "Error accessing Key Store: " + e.getMessage(),
                                        e );
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException( "No algorithm available: " + e.getMessage(),
                                        e );
        } catch (SignatureException e) {
            throw new RuntimeException( "Signature Exception: " + e.getMessage(),
                                        e );
        }
    }

    public void onAdd( DialectRuntimeRegistry registry,
                       ClassLoader rootClassLoader ) {
        this.rootClassLoader = rootClassLoader;
        this.classLoader = makeClassLoader();
    }

    public void onRemove() {

    }

    public void onBeforeExecute() {
        if ( isDirty() ) {
            reload();
        } else if (!this.wireList.isEmpty()) {
            try {
                // wire all remaining resources
                int wireListSize = this.wireList.size();
                if (wireListSize < 100) {
                    wireAll(classLoader, invokerLookups, this.wireList);
                } else {
                    wireInParallel(wireListSize);
                }
            } catch (Exception e) {
                throw new RuntimeException( "Unable to wire up JavaDialect", e );
            }
        }

        this.wireList.clear();
    }

    private void wireInParallel(int wireListSize) throws Exception {
        final int parallelThread = Runtime.getRuntime().availableProcessors();
        CompletionService<Boolean> ecs = ExecutorProviderFactory.getExecutorProvider().getCompletionService();

        int size = wireListSize / parallelThread;
        for (int i = 1; i <= parallelThread; i++) {
            List<String> subList = wireList.subList((i-1) * size, i == parallelThread ? wireListSize : i * size);
            ecs.submit(new WiringExecutor(classLoader, invokerLookups, subList));
        }
        for (int i = 1; i <= parallelThread; i++) {
            ecs.take().get();
        }
    }

    private static void wireAll(ClassLoader classLoader, Map<String, Wireable> invokerLookups, List<String> wireList) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        for (String resourceName : wireList) {
            wire( classLoader, invokerLookups, convertResourceToClassName( resourceName ) );
        }
    }

    private static class WiringExecutor implements Callable<Boolean> {
        private final ClassLoader classLoader;
        private final Map<String, Wireable> invokerLookups;
        private final List<String> wireList;

        private WiringExecutor(ClassLoader classLoader, Map<String, Wireable> invokerLookups, List<String> wireList) {
            this.classLoader = classLoader;
            this.invokerLookups = invokerLookups;
            this.wireList = wireList;
        }

        public Boolean call() throws Exception {
            wireAll(classLoader, invokerLookups, wireList);
            return true;
        }
    }

    public DialectRuntimeData clone( DialectRuntimeRegistry registry,
                                     ClassLoader rootClassLoader ) {
        return clone( registry, rootClassLoader, false );
    }

    public DialectRuntimeData clone( DialectRuntimeRegistry registry,
                                     ClassLoader rootClassLoader,
                                     boolean excludeClasses ) {
        DialectRuntimeData cloneOne = new JavaDialectRuntimeData();
        cloneOne.merge( registry,
                        this,
                        excludeClasses );
        cloneOne.onAdd( registry,
                        rootClassLoader );
        return cloneOne;
    }

    public void merge( DialectRuntimeRegistry registry,
                DialectRuntimeData newData ) {
        // false for backward compatibility, should probably be true by default
        merge(registry, newData, false);
    }

    public void merge( DialectRuntimeRegistry registry, DialectRuntimeData newData, boolean excludeClasses ) {
        JavaDialectRuntimeData newJavaData = (JavaDialectRuntimeData) newData;

        // First update the binary files
        // @todo: this probably has issues if you add classes in the incorrect order - functions, rules, invokers.
        for (String resourceName : newJavaData.getStore().keySet()) {
            if ( ! excludeClasses || ! newJavaData.classLookups.containsKey( resourceName ) ) {
                write( resourceName,
                       newJavaData.read( resourceName ) );
            }
        }

        // Add invokers
        putAllInvokers( newJavaData.invokerLookups );

        if ( ! excludeClasses ) {
            putAllClassDefinitions( newJavaData.classLookups );
        }

    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void setDirty( boolean dirty ) {
        this.dirty = dirty;
    }

    public Map<String, byte[]> getStore() {
        return store;
    }

    public byte[] getBytecode(String resourceName) {
        byte[] bytecode = null;
        if (store != null) {
            bytecode = store.get(resourceName);
        }
        if (bytecode == null && rootClassLoader instanceof ProjectClassLoader) {
            bytecode = ((ProjectClassLoader)rootClassLoader).getBytecode(resourceName);
        }
        return bytecode;
    }

    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    public ClassLoader getRootClassLoader() {
        return rootClassLoader;
    }

    public void removeRule( KnowledgePackageImpl pkg,
                            RuleImpl rule ) {

        if (!( rule instanceof QueryImpl)) {
            // Query's don't have a consequence, so skip those
            final String consequenceName = rule.getConsequence().getClass().getName();

            // check for compiled code and remove if present.
            if (remove( consequenceName )) {
                removeClasses( rule.getLhs() );

                // Now remove the rule class - the name is a subset of the consequence name
                String sufix = StringUtils.ucFirst( rule.getConsequence().getName() ) + "ConsequenceInvoker";
                remove( consequenceName.substring( 0,
                                                   consequenceName.indexOf( sufix ) ) );
            }
        }
    }

    public void removeFunction( KnowledgePackageImpl pkg, Function function ) {
        remove( pkg.getName() + "." + StringUtils.ucFirst( function.getName() ) );
    }

    private void removeClasses( final ConditionalElement ce ) {
        if (ce instanceof GroupElement) {
            final GroupElement group = (GroupElement) ce;
            for (final Object object : group.getChildren()) {
                if (object instanceof ConditionalElement) {
                    removeClasses((ConditionalElement) object);
                } else if (object instanceof Pattern) {
                    removeClasses((Pattern) object);
                }
            }
        } else if (ce instanceof EvalCondition) {
            remove( ( (EvalCondition) ce ).getEvalExpression().getClass().getName() );
        }
    }

    private void removeClasses( final Pattern pattern ) {
        for (final Constraint object : pattern.getConstraints()) {
            if (object instanceof PredicateConstraint) {
                remove(((PredicateConstraint) object).getPredicateExpression().getClass().getName());
            }
        }
    }

    public byte[] read( final String resourceName ) {
        return getStore().get( resourceName );
    }

    public synchronized void write( String resourceName, byte[] clazzData ) {
        if (getStore().put( resourceName,
                            clazzData ) != null) {
            this.dirty = true;

            if (!this.wireList.isEmpty()) {
                this.wireList.clear();
            }
        } else if (!this.dirty) {
            try {
                if (this.wireList == Collections.<String> emptyList()) {
                    this.wireList = new ArrayList<String>();
                }
                this.wireList.add( resourceName );
            } catch (final Exception e) {
                e.printStackTrace();
                throw new RuntimeException( e );
            }
        }
    }

    private static void wire( ClassLoader classLoader, Map<String, Wireable> invokerLookups, String className ) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        Wireable invoker = invokerLookups.get(className);
        if (invoker != null) {
            wire( classLoader, className, invoker, false );
        }
    }

    private static void wire( ClassLoader classLoader, String className, Wireable invoker, boolean reload ) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        if (reload && invoker instanceof Wireable.Immutable && (( Wireable.Immutable ) invoker).isInitialized()) {
            return;
        }

        final Class clazz = classLoader.loadClass( className );
        if (clazz != null) {
            invoker.wire( clazz.newInstance() );
        } else {
            throw new ClassNotFoundException( className );
        }
    }

    public boolean remove( final String resourceName ) {
        invokerLookups.remove( resourceName );
        if (getStore().remove( convertClassToResourcePath( resourceName ) ) != null) {
            this.wireList.remove( resourceName );
            // we need to make sure the class is removed from the classLoader
            // reload();
            this.dirty = true;
            return true;
        }
        return false;
    }

    /**
     * This class drops the classLoader and reloads it. During this process  it must re-wire all the invokeables.
     */
    public void reload() {
        // drops the classLoader and adds a new one
        this.classLoader = makeClassLoader();

        // Wire up invokers
        try {
            for (Entry<String, Wireable> entry : invokerLookups.entrySet()) {
                wire( classLoader, entry.getKey(), entry.getValue(), true );
            }
        } catch (final ClassNotFoundException e) {
            throw new RuntimeException( e );
        } catch (final InstantiationError e) {
            throw new RuntimeException( e );
        } catch (final IllegalAccessException e) {
            throw new RuntimeException( e );
        } catch (final InstantiationException e) {
            throw new RuntimeException( e );
        }

        this.dirty = false;
    }

    public void clear() {
        getStore().clear();
        invokerLookups.clear();
        reload();
    }

    public String toString() {
        return this.getClass().getName() + getStore().toString();
    }

    public void putInvoker( String className, Wireable invoker ) {
        invokerLookups.put(className, invoker);
    }

    public void putAllInvokers( final Map<String, Wireable> invokers ) {
        invokerLookups.putAll( invokers );
    }

    public void removeInvoker( final String className ) {
        invokerLookups.remove(className);
    }

    public void putClassDefinition( final String className,
                                    final byte[] classDef ) {
        classLookups.put( className, classDef );
    }

    public void putAllClassDefinitions( final Map classDefinitions ) {
        classLookups.putAll(classDefinitions);
    }

    public byte[] getClassDefinition( String className ) {
        return this.classLookups.computeIfAbsent( className, name -> rootClassLoader instanceof ProjectClassLoader ?
                                                                     ((ProjectClassLoader)rootClassLoader).getBytecode(name) :
                                                                     null);
    }

    public void removeClassDefinition( final String className ) {
        classLookups.remove( className );
    }

    private ClassLoader makeClassLoader() {
        return ClassUtils.isAndroid() ?
                (ClassLoader) ClassUtils.instantiateObject(
                        "org.drools.android.DexPackageClassLoader", null, this, this.rootClassLoader)
                : new PackageClassLoader( this, this.rootClassLoader );
    }

    /**
     * This is an Internal Drools Class
     */
    public static class PackageClassLoader extends ClassLoader implements FastClassLoader {

        private final ConcurrentHashMap<String, Object> parallelLockMap = new ConcurrentHashMap<String, Object>();

        protected JavaDialectRuntimeData store;

        private Set<String> existingPackages = new ConcurrentSkipListSet<String>();

        public PackageClassLoader( JavaDialectRuntimeData store,
                                   ClassLoader rootClassLoader ) {
            super( rootClassLoader );
            this.store = store;
        }

        public Class<?> loadClass( final String name,
                                   final boolean resolve ) throws ClassNotFoundException {
            Class<?> cls = fastFindClass( name );

            if (cls == null) {
                ClassLoader parent = getParent();
                cls = parent.loadClass( name );
            }

            if (cls == null) {
                throw new ClassNotFoundException( "Unable to load class: " + name );
            }

            return cls;
        }

        public Class<?> fastFindClass( final String name ) {
            Class<?> cls = findLoadedClass( name );

            if (cls == null) {
                Object lock = getLockObject(name);
                synchronized (lock) {
                    cls = findLoadedClass( name );
                    if (cls == null) {
                        try {
                            cls = internalDefineClass( name, this.store.read( convertClassToResourcePath( name ) ) );
                        } finally {
                            releaseLockObject( name );
                        }
                    }
                }
            }

            return cls;
        }

        private Class<?> internalDefineClass( String name, byte[] clazzBytes ) {
            if ( clazzBytes == null ) {
                return null;
            }
            String pkgName = name.substring( 0,
                                             name.lastIndexOf( '.' ) );

            if ( !existingPackages.contains( pkgName ) ) {
                synchronized (this) {
                    if ( getPackage( pkgName ) == null ) {
                        definePackage( pkgName,
                                       "", "", "", "", "", "",
                                       null );
                    }
                    existingPackages.add( pkgName );
                }
            }

            Class<?> cls = defineClass( name,
                                        clazzBytes,
                                        0,
                                        clazzBytes.length,
                                        PROTECTION_DOMAIN );
            resolveClass( cls );
            return cls;
        }

        public InputStream getResourceAsStream( final String name ) {
            final byte[] clsBytes = this.store.read( name );
            if (clsBytes != null) {
                return new ByteArrayInputStream( clsBytes );
            }
            return null;
        }

        public URL getResource( String name ) {
            return null;
        }

        public Enumeration<URL> getResources( String name ) throws IOException {
            return new Enumeration<URL>() {

                public boolean hasMoreElements() {
                    return false;
                }

                public URL nextElement() {
                    throw new NoSuchElementException();
                }
            };
        }

        private Object getLockObject(String className) {
            Object newLock = new Object();
            Object lock = parallelLockMap.putIfAbsent(className, newLock);
            return lock != null ? lock : newLock;
        }

        private void releaseLockObject(String className) {
            parallelLockMap.remove( className );
        }
    }
}
