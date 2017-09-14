package com.graphhopper.routing.util;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.profiles.*;
import com.graphhopper.routing.weighting.FastestCarWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

public class EncodingManagerNewTest {

    private EncodingManager createEncodingManager() {
        // do not add surface property to test exception below
        TagsParserOSM parser = new TagsParserOSM();
        final Map<String, Double> speedMap = TagParserFactory.Car.createSpeedMap();
        ReaderWayFilter filter = new ReaderWayFilter() {
            @Override
            public boolean accept(ReaderWay way) {
                return speedMap.containsKey(way.getTag("highway"));
            }
        };
        return new EncodingManager.Builder(parser, 4).
                addGlobalEncodedValues().
                add(TagParserFactory.Car.createAverageSpeed(new DecimalEncodedValue("average_speed", 5, 0, 5, true), speedMap)).
                add(TagParserFactory.Car.createMaxSpeed(new DecimalEncodedValue("max_speed", 5, 50, 5, false), filter)).
                add(TagParserFactory.Car.createAccess(new BooleanEncodedValue("access", true), filter)).
                add(TagParserFactory.Truck.createMaxWeight(new DecimalEncodedValue("weight", 5, 5, 1, false), filter)).
                add(TagParserFactory.createRoadClass(new StringEncodedValue("highway",
                        Arrays.asList("primary", "secondary", "tertiary"), "tertiary"))).
                build();
    }

    @Test
    public void importValues() {
        ReaderWay readerWay = new ReaderWay(0);
        readerWay.setTag("maxspeed", "30");
        readerWay.setTag("weight", "4");
        readerWay.setTag("highway", "tertiary");
        readerWay.setTag("surface", "mud");

        EncodingManager encodingManager = createEncodingManager();
        GraphHopperStorage g = new GraphBuilder(encodingManager).create();
        EdgeIteratorState edge = GHUtility.createEdge(g, encodingManager.getBooleanEncodedValue(TagParserFactory.Car.ACCESS), 0, 1, 10, true);

        encodingManager.applyWayTags(readerWay, edge);

        DecimalEncodedValue maxSpeed = encodingManager.getEncodedValue("max_speed", DecimalEncodedValue.class);
        IntEncodedValue weight = encodingManager.getEncodedValue("weight", IntEncodedValue.class);
        StringEncodedValue highway = encodingManager.getEncodedValue("highway", StringEncodedValue.class);
        assertEquals(30d, edge.get(maxSpeed), 1d);
        assertEquals(4, edge.get(weight));
        assertEquals("tertiary", edge.get(highway));
        // access internal int representation - is this good or bad?
        assertEquals(2, edge.get((IntEncodedValue) highway));

        try {
            encodingManager.getEncodedValue("not_existing", IntEncodedValue.class);
            assertTrue(false);
        } catch (Exception ex) {
        }
    }

    @Test
    public void testDefaultValue() {
        ReaderWay readerWay = new ReaderWay(0);
        readerWay.setTag("highway", "tertiary");

        EncodingManager encodingManager = createEncodingManager();
        GraphHopperStorage g = new GraphBuilder(encodingManager).create();
        EdgeIteratorState edge = GHUtility.createEdge(g, encodingManager.getBooleanEncodedValue(TagParserFactory.Car.ACCESS), 0, 1, 10, true);

        encodingManager.applyWayTags(readerWay, edge);

        IntEncodedValue weight = encodingManager.getEncodedValue("weight", IntEncodedValue.class);
        assertEquals(5, edge.get(weight));
        DecimalEncodedValue speed = encodingManager.getEncodedValue("max_speed", DecimalEncodedValue.class);
        assertEquals(50, edge.get(speed), .1);
    }

    // TODO currently we do not throw an exception in TagsParserOSM.parse
    @Ignore
    @Test
    public void testValueBoundaryCheck() {
        ReaderWay readerWay = new ReaderWay(0);
        readerWay.setTag("maxspeed", "180");

        EncodingManager encodingManager = createEncodingManager();
        GraphHopperStorage g = new GraphBuilder(encodingManager).create();
        EdgeIteratorState edge = GHUtility.createEdge(g, encodingManager.getBooleanEncodedValue(TagParserFactory.Car.ACCESS), 0, 1, 10, true);

        try {
            encodingManager.applyWayTags(readerWay, edge);
            assertTrue(false);
        } catch (Exception ex) {
        }
    }

    @Test
    public void testNotInitializedProperty() {
        EncodingManager encodingManager = createEncodingManager();
        GraphHopperStorage g = new GraphBuilder(encodingManager).create();
        EdgeIteratorState edge = GHUtility.createEdge(g, encodingManager.getBooleanEncodedValue(TagParserFactory.Car.ACCESS), 0, 1, 10, true);
        StringEncodedValue surface = new StringEncodedValue("surface", Arrays.asList("mud", "something"), "something");
        try {
            edge.get(surface);
            assertTrue(false);
        } catch (AssertionError ex) {
        }
    }

    @Test
    public void testWeighting() {
        EncodingManager encodingManager = createEncodingManager();
        Weighting weighting = new FastestCarWeighting(encodingManager, "some_weighting");
        GraphHopperStorage g = new GraphBuilder(encodingManager).create();
        EdgeIteratorState edge = GHUtility.createEdge(g, encodingManager.getBooleanEncodedValue(TagParserFactory.Car.ACCESS), 0, 1, 10, true);

        DecimalEncodedValue maxSpeed = encodingManager.getEncodedValue("average_speed", DecimalEncodedValue.class);
        edge.set(maxSpeed, 26d);

        assertEquals(10 / ((26 / 5 * 5) * 0.9) * 3600, weighting.calcMillis(edge, false, -1), 1);
    }

    @Test
    public void testDirectionDependentBit() {
        EncodingManager encodingManager = createEncodingManager();
        GraphHopperStorage g = new GraphBuilder(encodingManager).create();
        EdgeIteratorState edge = GHUtility.createEdge(g, encodingManager.getBooleanEncodedValue(TagParserFactory.Car.ACCESS), 0, 1, 10, true);

        ReaderWay readerWay = new ReaderWay(0);
        readerWay.setTag("maxspeed", "30");
        readerWay.setTag("highway", "tertiary");
        readerWay.setTag("oneway", "yes");
        encodingManager.applyWayTags(readerWay, edge);

        BooleanEncodedValue access = encodingManager.getEncodedValue("access", BooleanEncodedValue.class);
        assertTrue(edge.get(access));
        assertFalse(edge.getReverse(access));
        assertFalse(edge.detach(true).get(access));

        // add new edge and apply its associated OSM tags
        EdgeIteratorState edge2 = g.edge(0, 2);
        readerWay = new ReaderWay(2);
        readerWay.setTag("highway", "primary");
        encodingManager.applyWayTags(readerWay, edge2);

        // assert that if the properties are cached that it is properly done
        EdgeExplorer explorer = g.createEdgeExplorer();
        EdgeIterator iter = explorer.setBaseNode(0);
        assertTrue(iter.next());
        assertEquals(2, iter.getAdjNode());
        assertTrue(iter.get(access));
        assertTrue(iter.detach(true).get(access));
        assertTrue(iter.getReverse(access));

        assertTrue(iter.next());
        assertEquals(1, iter.getAdjNode());
        assertTrue(iter.get(access));
        assertFalse(iter.detach(true).get(access));
        assertFalse(iter.getReverse(access));

        assertFalse(iter.next());
    }

    @Test
    public void testDirectionDependentDecimal() {
        final DecimalEncodedValue directed = new DecimalEncodedValue("directed_speed", 10, 0, 1, true);

        TagParser directedSpeedParser = new TagParser() {

            @Override
            public String getName() {
                return "directed_speed";
            }

            @Override
            public void parse(IntsRef ints, ReaderWay way) {
                final double speed = AbstractFlagEncoder.parseSpeed(way.getTag("maxspeed"));
                final double speedFW = AbstractFlagEncoder.parseSpeed(way.getTag("maxspeed:forward"));
                directed.setDecimal(false, ints, speedFW > 0 ? speedFW : speed);
                directed.setDecimal(true, ints, speed);
            }

            @Override
            public EncodedValue getEncodedValue() {
                return directed;
            }

            @Override
            public ReaderWayFilter getReadWayFilter() {
                return TagParserFactory.ACCEPT_IF_HIGHWAY;
            }
        };

        TagsParserOSM parser = new TagsParserOSM();
        EncodingManager encodingManager = new EncodingManager.Builder(parser, 4).
                add(directedSpeedParser).
                build();

        GraphHopperStorage g = new GraphBuilder(encodingManager).create();
        EdgeIteratorState edge = GHUtility.createEdge(g, encodingManager.getBooleanEncodedValue(TagParserFactory.Car.ACCESS), 0, 1, 10, true);

        ReaderWay readerWay = new ReaderWay(0);
        readerWay.setTag("maxspeed", "30");
        readerWay.setTag("maxspeed:forward", "50");
        readerWay.setTag("highway", "tertiary");
        encodingManager.applyWayTags(readerWay, edge);

        assertEquals(50, edge.get(directed), .1);

        EdgeIteratorState reverseEdge = edge.detach(true);
        assertEquals(30, reverseEdge.get(directed), .1);

        assertEquals(50, edge.get(directed), .1);
    }

    @Test
    public void testSkipWaysWithoutHighwayTag() {
        EncodingManager encodingManager = createEncodingManager();
        CarFlagEncoder encoder = (CarFlagEncoder) encodingManager.getEncoder("car");
        ReaderWay readerWay = new ReaderWay(0);
        readerWay.setTag("highway", "primary");
        assertTrue(encoder.getAccess(readerWay).isWay());

        // unknown value or no highway at all triggers filters from some EncodedValues
        readerWay.setTag("highway", "xy");
        assertFalse(encoder.getAccess(readerWay).isWay());

        readerWay.removeTag("highway");
        assertFalse(encoder.getAccess(readerWay).isWay());
    }

    @Test
    public void testMoreThan4Bytes() {
        // TODO
        // return new EncodingManager(parser, 8).init(Arrays.asList(maxSpeed, weight, highway));
    }

    @Test
    public void testSplittingAtVirtualEdges() {
        // TODO
    }

    @Test
    public void testNonOSMDataSet() {
        // TODO use completely different 'tagging' and still feed the same properties
        // or introduce a converter which allows us to intercept input and convert to OSM-alike tagging
    }
}