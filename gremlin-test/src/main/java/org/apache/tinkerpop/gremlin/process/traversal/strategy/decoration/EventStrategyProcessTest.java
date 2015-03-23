package org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration;

import org.apache.tinkerpop.gremlin.FeatureRequirement;
import org.apache.tinkerpop.gremlin.FeatureRequirementSet;
import org.apache.tinkerpop.gremlin.process.AbstractGremlinProcessTest;
import org.apache.tinkerpop.gremlin.process.UseEngine;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalEngine;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.event.GraphChangedListener;
import org.apache.tinkerpop.gremlin.structure.Compare;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
@UseEngine(TraversalEngine.Type.STANDARD)
public class EventStrategyProcessTest extends AbstractGremlinProcessTest {

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    public void shouldTriggerAddVertex() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        graph.addVertex("some", "thing");
        final GraphTraversalSource gts = create(eventStrategy);
        gts.V().addV("any", "thing").next();

        tryCommit(graph, g -> assertEquals(1, IteratorUtils.count(gts.V().has("any", Compare.eq, "thing"))));
        assertEquals(1, listener1.addVertexEventRecorded());
        assertEquals(1, listener2.addVertexEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    public void shouldTriggerAddVertexFromStart() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        graph.addVertex("some", "thing");
        final GraphTraversalSource gts = create(eventStrategy);
        gts.addV("any", "thing").next();

        tryCommit(graph, g -> assertEquals(1, IteratorUtils.count(gts.V().has("any", Compare.eq, "thing"))));
        assertEquals(1, listener1.addVertexEventRecorded());
        assertEquals(1, listener2.addVertexEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.SIMPLE)
    public void shouldTriggerAddEdge() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex v = graph.addVertex();
        v.addEdge("self", v);

        final GraphTraversalSource gts = create(eventStrategy);
        gts.V(v).addOutE("self", v).next();

        tryCommit(graph, g -> assertEquals(2, IteratorUtils.count(gts.E())));

        assertEquals(0, listener1.addVertexEventRecorded());
        assertEquals(0, listener2.addVertexEventRecorded());

        assertEquals(1, listener1.addEdgeEventRecorded());
        assertEquals(1, listener2.addEdgeEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.SIMPLE)
    public void shouldTriggerAddEdgeByPath() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex v = graph.addVertex();
        v.addEdge("self", v);

        final GraphTraversalSource gts = create(eventStrategy);
        gts.V(v).as("a").addOutE("self", "a").next();

        tryCommit(graph, g -> assertEquals(2, IteratorUtils.count(gts.E())));

        assertEquals(0, listener1.addVertexEventRecorded());
        assertEquals(0, listener2.addVertexEventRecorded());

        assertEquals(1, listener1.addEdgeEventRecorded());
        assertEquals(1, listener2.addEdgeEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    public void shouldTriggerAddVertexPropertyAdded() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex vSome = graph.addVertex("some", "thing");
        vSome.property("that", "thing");
        final GraphTraversalSource gts = create(eventStrategy);
        gts.V().addV("any", "thing").property(VertexProperty.Cardinality.single, "this", "thing").next();

        tryCommit(graph, g -> assertEquals(1, IteratorUtils.count(gts.V().has("this", Compare.eq, "thing"))));

        assertEquals(1, listener1.addVertexEventRecorded());
        assertEquals(1, listener2.addVertexEventRecorded());
        assertEquals(1, listener2.vertexPropertyChangedEventRecorded());
        assertEquals(1, listener1.vertexPropertyChangedEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    public void shouldTriggerAddVertexPropertyChanged() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex vSome = graph.addVertex("some", "thing");
        vSome.property("that", "thing");
        final GraphTraversalSource gts = create(eventStrategy);
        final Vertex vAny = gts.V().addV("any", "thing").next();
        gts.V(vAny).property(VertexProperty.Cardinality.single, "any", "thing else").next();

        tryCommit(graph, g -> assertEquals(1, IteratorUtils.count(gts.V().has("any", Compare.eq, "thing else"))));

        assertEquals(1, listener1.addVertexEventRecorded());
        assertEquals(1, listener2.addVertexEventRecorded());
        assertEquals(1, listener2.vertexPropertyChangedEventRecorded());
        assertEquals(1, listener1.vertexPropertyChangedEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_META_PROPERTIES)
    public void shouldTriggerAddVertexPropertyPropertyChanged() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex vSome = graph.addVertex("some", "thing");
        vSome.property("that", "thing", "is", "good");
        final GraphTraversalSource gts = create(eventStrategy);
        final Vertex vAny = gts.V().addV("any", "thing").next();
        gts.V(vAny).properties("any").property("is", "bad").next();

        tryCommit(graph, g -> assertEquals(1, IteratorUtils.count(gts.V().has("any", Compare.eq, "thing"))));

        assertEquals(1, listener1.addVertexEventRecorded());
        assertEquals(1, listener2.addVertexEventRecorded());
        assertEquals(1, listener2.vertexPropertyPropertyChangedEventRecorded());
        assertEquals(1, listener1.vertexPropertyPropertyChangedEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.SIMPLE)
    public void shouldTriggerAddEdgePropertyAdded() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex v = graph.addVertex();
        v.addEdge("self", v);

        final GraphTraversalSource gts = create(eventStrategy);
        gts.V(v).addOutE("self", v).property("some", "thing").next();

        tryCommit(graph, g -> assertEquals(1, IteratorUtils.count(gts.E().has("some", "thing"))));

        assertEquals(0, listener1.addVertexEventRecorded());
        assertEquals(0, listener2.addVertexEventRecorded());

        assertEquals(1, listener1.addEdgeEventRecorded());
        assertEquals(1, listener2.addEdgeEventRecorded());

        assertEquals(1, listener2.edgePropertyChangedEventRecorded());
        assertEquals(1, listener1.edgePropertyChangedEventRecorded());

    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.SIMPLE)
    public void shouldTriggerEdgePropertyChanged() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex v = graph.addVertex();
        final Edge e = v.addEdge("self", v);
        e.property("some", "thing");

        final GraphTraversalSource gts = create(eventStrategy);
        gts.E(e).property("some", "other thing").next();

        tryCommit(graph, g -> assertEquals(1, IteratorUtils.count(gts.E().has("some", "other thing"))));

        assertEquals(0, listener1.addVertexEventRecorded());
        assertEquals(0, listener2.addVertexEventRecorded());

        assertEquals(0, listener1.addEdgeEventRecorded());
        assertEquals(0, listener2.addEdgeEventRecorded());

        assertEquals(1, listener2.edgePropertyChangedEventRecorded());
        assertEquals(1, listener1.edgePropertyChangedEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    public void shouldTriggerRemoveVertex() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        graph.addVertex("some", "thing");
        final GraphTraversalSource gts = create(eventStrategy);
        gts.V().drop().iterate();

        tryCommit(graph, g -> assertEquals(0, IteratorUtils.count(gts.V())));

        assertEquals(1, listener1.vertexRemovedEventRecorded());
        assertEquals(1, listener2.vertexRemovedEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    public void shouldTriggerRemoveEdge() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex v = graph.addVertex("some", "thing");
        v.addEdge("self", v);
        final GraphTraversalSource gts = create(eventStrategy);
        gts.E().drop().iterate();

        tryCommit(graph);

        assertEquals(1, listener1.edgeRemovedEventRecorded());
        assertEquals(1, listener2.edgeRemovedEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    public void shouldTriggerRemoveVertexProperty() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        graph.addVertex("some", "thing");
        final GraphTraversalSource gts = create(eventStrategy);
        gts.V().properties().drop().iterate();

        tryCommit(graph, g -> assertEquals(0, IteratorUtils.count(gts.V().properties())));

        assertEquals(1, listener1.vertexPropertyRemovedEventRecorded());
        assertEquals(1, listener2.vertexPropertyRemovedEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.SIMPLE)
    public void shouldTriggerRemoveEdgeProperty() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex v = graph.addVertex();
        v.addEdge("self", v, "some", "thing");
        final GraphTraversalSource gts = create(eventStrategy);
        gts.E().properties().drop().iterate();

        tryCommit(graph, g -> assertEquals(0, IteratorUtils.count(gts.E().properties())));

        assertEquals(1, listener1.edgePropertyRemovedEventRecorded());
        assertEquals(1, listener2.edgePropertyRemovedEventRecorded());
    }

    @Test
    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_META_PROPERTIES)
    public void shouldTriggerAddVertexPropertyPropertyRemoved() {
        final StubGraphChangedListener listener1 = new StubGraphChangedListener();
        final StubGraphChangedListener listener2 = new StubGraphChangedListener();
        final EventStrategy eventStrategy = EventStrategy.build()
                .addListener(listener1)
                .addListener(listener2).create();

        final Vertex vSome = graph.addVertex("some", "thing");
        vSome.property("that", "thing", "is", "good");
        final GraphTraversalSource gts = create(eventStrategy);
        final Vertex vAny = gts.V().addV("any", "thing").next();
        gts.V(vAny).properties("any").property("is", "bad").next();
        gts.V(vAny).properties("any").properties("is").drop().iterate();

        tryCommit(graph, g -> assertEquals(1, IteratorUtils.count(gts.V().has("any", Compare.eq, "thing"))));

        assertEquals(1, listener1.addVertexEventRecorded());
        assertEquals(1, listener2.addVertexEventRecorded());
        assertEquals(1, listener2.vertexPropertyPropertyChangedEventRecorded());
        assertEquals(1, listener1.vertexPropertyPropertyChangedEventRecorded());
        assertEquals(1, listener2.vertexPropertyPropertyRemovedEventRecorded());
        assertEquals(1, listener1.vertexPropertyPropertyRemovedEventRecorded());
    }

    private GraphTraversalSource create(final EventStrategy strategy) {
        return graphProvider.traversal(graph, strategy);
    }

    public static class StubGraphChangedListener implements GraphChangedListener {
        private final AtomicLong addEdgeEvent = new AtomicLong(0);
        private final AtomicLong addVertexEvent = new AtomicLong(0);
        private final AtomicLong vertexRemovedEvent = new AtomicLong(0);
        private final AtomicLong edgePropertyChangedEvent = new AtomicLong(0);
        private final AtomicLong vertexPropertyChangedEvent = new AtomicLong(0);
        private final AtomicLong vertexPropertyPropertyChangedEvent = new AtomicLong(0);
        private final AtomicLong edgePropertyRemovedEvent = new AtomicLong(0);
        private final AtomicLong vertexPropertyPropertyRemovedEvent = new AtomicLong(0);
        private final AtomicLong edgeRemovedEvent = new AtomicLong(0);
        private final AtomicLong vertexPropertyRemovedEvent = new AtomicLong(0);

        private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();

        public void reset() {
            addEdgeEvent.set(0);
            addVertexEvent.set(0);
            vertexRemovedEvent.set(0);
            edgePropertyChangedEvent.set(0);
            vertexPropertyChangedEvent.set(0);
            vertexPropertyPropertyChangedEvent.set(0);
            vertexPropertyPropertyRemovedEvent.set(0);
            edgePropertyRemovedEvent.set(0);
            edgeRemovedEvent.set(0);
            vertexPropertyRemovedEvent.set(0);

            order.clear();
        }

        public List<String> getOrder() {
            return new ArrayList<>(this.order);
        }

        @Override
        public void vertexAdded(final Vertex vertex) {
            addVertexEvent.incrementAndGet();
            order.add("v-added-" + vertex.id());
        }

        @Override
        public void vertexRemoved(final Vertex vertex) {
            vertexRemovedEvent.incrementAndGet();
            order.add("v-removed-" + vertex.id());
        }

        @Override
        public void edgeAdded(final Edge edge) {
            addEdgeEvent.incrementAndGet();
            order.add("e-added-" + edge.id());
        }

        @Override
        public void edgePropertyRemoved(final Edge element, final Property o) {
            edgePropertyRemovedEvent.incrementAndGet();
            order.add("e-property-removed-" + element.id() + "-" + o);
        }

        @Override
        public void vertexPropertyPropertyRemoved(final VertexProperty element, final Property o) {
            vertexPropertyPropertyRemovedEvent.incrementAndGet();
            order.add("vp-property-removed-" + element.id() + "-" + o);
        }

        @Override
        public void edgeRemoved(final Edge edge) {
            edgeRemovedEvent.incrementAndGet();
            order.add("e-removed-" + edge.id());
        }

        @Override
        public void vertexPropertyRemoved(final VertexProperty vertexProperty) {
            vertexPropertyRemovedEvent.incrementAndGet();
            order.add("vp-property-removed-" + vertexProperty.id());
        }

        @Override
        public void edgePropertyChanged(final Edge element, final Property oldValue, final Object setValue) {
            edgePropertyChangedEvent.incrementAndGet();
            order.add("e-property-chanaged-" + element.id());
        }

        @Override
        public void vertexPropertyPropertyChanged(final VertexProperty element, final Property oldValue, final Object setValue) {
            vertexPropertyPropertyChangedEvent.incrementAndGet();
            order.add("vp-property-changed-" + element.id());
        }

        @Override
        public void vertexPropertyChanged(final Vertex element, final Property oldValue, final Object setValue, final Object... vertexPropertyKeyValues) {
            vertexPropertyChangedEvent.incrementAndGet();
            order.add("v-property-changed-" + element.id());
        }

        public long addEdgeEventRecorded() {
            return addEdgeEvent.get();
        }

        public long addVertexEventRecorded() {
            return addVertexEvent.get();
        }

        public long vertexRemovedEventRecorded() {
            return vertexRemovedEvent.get();
        }

        public long edgeRemovedEventRecorded() {
            return edgeRemovedEvent.get();
        }

        public long edgePropertyRemovedEventRecorded() {
            return edgePropertyRemovedEvent.get();
        }

        public long vertexPropertyRemovedEventRecorded() {
            return vertexPropertyRemovedEvent.get();
        }

        public long vertexPropertyPropertyRemovedEventRecorded() {
            return vertexPropertyPropertyRemovedEvent.get();
        }

        public long edgePropertyChangedEventRecorded() {
            return edgePropertyChangedEvent.get();
        }

        public long vertexPropertyChangedEventRecorded() {
            return vertexPropertyChangedEvent.get();
        }

        public long vertexPropertyPropertyChangedEventRecorded() {
            return vertexPropertyPropertyChangedEvent.get();
        }
    }

}