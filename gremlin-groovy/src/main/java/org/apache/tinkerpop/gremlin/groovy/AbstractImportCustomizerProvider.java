/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.groovy;

import groovy.grape.Grape;
import groovy.json.JsonBuilder;
import org.apache.commons.configuration.Configuration;
import org.apache.tinkerpop.gremlin.algorithm.generator.AbstractGenerator;
import org.apache.tinkerpop.gremlin.groovy.function.GFunction;
import org.apache.tinkerpop.gremlin.groovy.loaders.GremlinLoader;
import org.apache.tinkerpop.gremlin.process.Scope;
import org.apache.tinkerpop.gremlin.process.T;
import org.apache.tinkerpop.gremlin.process.Traversal;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.clustering.peerpressure.PeerPressureVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.lambda.LambdaVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.PageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.process.graph.traversal.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.graph.traversal.__;
import org.apache.tinkerpop.gremlin.process.graph.traversal.step.TraversalOptionParent;
import org.apache.tinkerpop.gremlin.process.traversal.engine.ComputerTraversalEngine;
import org.apache.tinkerpop.gremlin.process.traversal.engine.StandardTraversalEngine;
import org.apache.tinkerpop.gremlin.process.util.metric.TraversalMetrics;
import org.apache.tinkerpop.gremlin.structure.Compare;
import org.apache.tinkerpop.gremlin.structure.Contains;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Operator;
import org.apache.tinkerpop.gremlin.structure.Order;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.io.GraphReader;
import org.apache.tinkerpop.gremlin.structure.io.graphml.GraphMLReader;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONReader;
import org.apache.tinkerpop.gremlin.structure.io.kryo.KryoReader;
import org.apache.tinkerpop.gremlin.structure.strategy.GraphStrategy;
import org.apache.tinkerpop.gremlin.structure.util.GraphFactory;
import org.apache.tinkerpop.gremlin.structure.util.batch.BatchGraph;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedElement;
import org.apache.tinkerpop.gremlin.util.Gremlin;
import org.apache.tinkerpop.gremlin.util.function.FunctionUtils;
import org.apache.tinkerpop.gremlin.util.tools.TimeUtils;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;

import java.util.HashSet;
import java.util.Set;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public abstract class AbstractImportCustomizerProvider implements ImportCustomizerProvider {
    protected static final String DOT_STAR = ".*";
    protected static final String EMPTY_STRING = "";
    protected static final String PERIOD = ".";

    protected final Set<String> extraImports = new HashSet<>();
    protected final Set<String> extraStaticImports = new HashSet<>();

    private static final Set<String> imports = new HashSet<>();
    private static final Set<String> staticImports = new HashSet<>();

    static {
        // graph structure
        imports.add(Graph.class.getPackage().getName() + DOT_STAR);
        imports.add(GraphStrategy.class.getPackage().getName() + DOT_STAR);
        imports.add(GraphFactory.class.getPackage().getName() + DOT_STAR);
        imports.add(BatchGraph.class.getPackage().getName() + DOT_STAR);
        imports.add(DetachedElement.class.getPackage().getName() + DOT_STAR);

        // graph process
        imports.add(Traversal.class.getPackage().getName() + DOT_STAR);
        imports.add(GraphComputer.class.getPackage().getName() + DOT_STAR);
        imports.add(GraphTraversal.class.getPackage().getName() + DOT_STAR);
        imports.add(ComputerTraversalEngine.class.getPackage().getName() + DOT_STAR);
        staticImports.add(__.class.getCanonicalName() + DOT_STAR);
        staticImports.add(TraversalOptionParent.Pick.class.getCanonicalName() + DOT_STAR);
        staticImports.add(StandardTraversalEngine.class.getCanonicalName() + DOT_STAR);
        staticImports.add(ComputerTraversalEngine.class.getCanonicalName() + DOT_STAR);

        // utils
        imports.add(Gremlin.class.getPackage().getName() + DOT_STAR);
        imports.add(GremlinLoader.class.getPackage().getName() + DOT_STAR);
        imports.add(FunctionUtils.class.getPackage().getName() + DOT_STAR);
        imports.add(GFunction.class.getPackage().getName() + DOT_STAR);
        imports.add(TraversalMetrics.class.getPackage().getName() + DOT_STAR);

        // IO packages
        imports.add(GraphReader.class.getPackage().getName() + DOT_STAR);
        imports.add(GraphMLReader.class.getPackage().getName() + DOT_STAR);
        imports.add(GraphSONReader.class.getPackage().getName() + DOT_STAR);
        imports.add(KryoReader.class.getPackage().getName() + DOT_STAR);

        // algorithms
        imports.add(AbstractGenerator.class.getPackage().getName() + DOT_STAR);
        imports.add(PeerPressureVertexProgram.class.getPackage().getName() + DOT_STAR);
        imports.add(PageRankVertexProgram.class.getPackage().getName() + DOT_STAR);
        imports.add(TraversalVertexProgram.class.getPackage().getName() + DOT_STAR);
        imports.add(LambdaVertexProgram.class.getPackage().getName() + DOT_STAR);

        // groovy extras
        imports.add(Grape.class.getCanonicalName());
        imports.add(JsonBuilder.class.getPackage().getName() + DOT_STAR);

        // external
        imports.add(Configuration.class.getPackage().getName() + DOT_STAR);

        staticImports.add(T.class.getCanonicalName() + DOT_STAR);
        staticImports.add(Direction.class.getCanonicalName() + DOT_STAR);
        staticImports.add(Compare.class.getCanonicalName() + DOT_STAR);
        staticImports.add(Contains.class.getCanonicalName() + DOT_STAR);
        staticImports.add(Order.class.getCanonicalName() + DOT_STAR);
        staticImports.add(Operator.class.getCanonicalName() + DOT_STAR);
        staticImports.add(Scope.class.getCanonicalName() + DOT_STAR);
        staticImports.add(TimeUtils.class.getCanonicalName() + DOT_STAR);
        staticImports.add(VertexProperty.Cardinality.class.getCanonicalName() + DOT_STAR);
    }

    @Override
    public CompilationCustomizer getCompilationCustomizer() {
        final ImportCustomizer ic = new ImportCustomizer();

        processImports(ic, imports);
        processStaticImports(ic, staticImports);
        processImports(ic, extraImports);
        processStaticImports(ic, extraStaticImports);

        return ic;
    }

    @Override
    public Set<String> getImports() {
        return imports;
    }

    @Override
    public Set<String> getStaticImports() {
        return staticImports;
    }

    @Override
    public Set<String> getExtraImports() {
        return extraImports;
    }

    @Override
    public Set<String> getExtraStaticImports() {
        return extraStaticImports;
    }

    public Set<String> getAllImports() {
        final Set<String> allImports = new HashSet<>();
        allImports.addAll(imports);
        allImports.addAll(staticImports);
        allImports.addAll(extraImports);
        allImports.addAll(extraStaticImports);

        return allImports;
    }

    protected static void processStaticImports(final ImportCustomizer ic, final Set<String> staticImports) {
        for (final String staticImport : staticImports) {
            if (staticImport.endsWith(DOT_STAR)) {
                ic.addStaticStars(staticImport.replace(DOT_STAR, EMPTY_STRING));
            } else {
                final int place = staticImport.lastIndexOf(PERIOD);
                ic.addStaticImport(staticImport.substring(0, place), staticImport.substring(place + 1));
            }
        }
    }

    protected static void processImports(final ImportCustomizer ic, final Set<String> imports) {
        for (final String imp : imports) {
            if (imp.endsWith(DOT_STAR)) {
                ic.addStarImports(imp.replace(DOT_STAR, EMPTY_STRING));
            } else {
                ic.addImports(imp);
            }
        }
    }
}