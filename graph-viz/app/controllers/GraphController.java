package controllers;

import clustering.IKmeans;
import clustering.Kmeans;
import clustering.Clustering;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edgeBundling.ForceBundling;
import models.*;
import play.mvc.*;
import actors.WebSocketActor;
import treeCut.TreeCut;
import utils.DatabaseUtils;
import utils.IncrementalQuery;
import utils.PropertiesUtil;

import java.sql.*;
import java.text.ParseException;
import java.util.*;

/**
 * This controller contains an action to handle HTTP requests to the
 * application's home page.
 */

public class GraphController extends Controller {

    // Indicates the sending process is completed
    private static final String finished = "Y";
    // Indicates the sending process is not completed
    private static final String unfinished = "N";
    // hierarchical structure for HGC algorithm
    private Clustering clustering = new Clustering(0, 17);
    private IKmeans iKmeans;
    private Kmeans kmeans;
    // Incremental edge data
    private HashMap<Edge, Integer> totalEdges = new HashMap<>();
    private List<Point> totalPoints = new ArrayList<>();
    private List<Point> batchPoints = new ArrayList<>();
    private ObjectMapper objectMapper = new ObjectMapper();
    private ObjectNode dataNode;
    private Parser parser = new Parser();
    // Size of resultSet

    /**
     * Dispatcher for the request message.
     *
     * @param query received query message
     * @param actor WebSocket actor to return response.
     */
    public void dispatcher(String query, WebSocketActor actor) {
        // Heartbeat package handler
        // WebSocket will automatically close after several seconds
        // To keep the state, maintain WebSocket connection is a must
        if (query.equals("")) {
            return;
        }
        dataNode = objectMapper.createObjectNode();
        // Parse the request message with JSON structure
        parser.parse(query);
        // Option indicates the request type
        // 1: incremental data query
        // 2: point and drawPoints
        // 3: edge and bundled edge and tree cut
        // others: invalid
        switch (parser.getOption()) {
            case 0:
                IncrementalQuery incrementalQuery = new IncrementalQuery();
                incrementalQuery.readProperties(parser.getEndDate());
                try {
                    doQuery(incrementalQuery.getStart(), incrementalQuery.getEnd());
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                break;
            case 1:
                drawPoints();
                break;
            case 2:
                drawEdges();
                break;
            default:
                System.err.println("Internal error: no option included");
                break;
        }
        dataNode.put("option", parser.getOption());
        dataNode.put("timestamp", parser.getTimestamp());
        actor.returnData(dataNode.toString());
    }

    private void doQuery(String start, String end) throws SQLException {
        Connection conn = DatabaseUtils.getConnection();
        PreparedStatement state = DatabaseUtils.prepareStatement(parser.getQuery(), conn, start, end);
        ResultSet resultSet = null;
        try {
            resultSet = state.executeQuery();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        bindFields(end);
        if (resultSet != null) {
            loadData(resultSet);
            if (parser.getClusteringAlgorithm() == 0) {
                loadHGC();
            } else if (parser.getClusteringAlgorithm() == 1) {
                loadIKmeans();
            } else if (parser.getClusteringAlgorithm() == 2) {
                bindFields(PropertiesUtil.lastDate);
                state = DatabaseUtils.prepareStatement(parser.getQuery(), conn, PropertiesUtil.firstDate, PropertiesUtil.lastDate);
                loadData(state.executeQuery());
                loadKmeans();
            }
            resultSet.close();
        }
        state.close();
    }

    private void loadData(ResultSet resultSet) throws SQLException {
        batchPoints.clear();
        while (resultSet.next()) {
            Point from = new Point(resultSet.getDouble("from_longitude"), resultSet.getDouble("from_latitude"));
            Point to = new Point(resultSet.getDouble("to_longitude"), resultSet.getDouble("to_latitude"));
            Edge currentEdge = new Edge(from, to);
            putEdgeIntoMap(totalEdges, currentEdge, 1);
            totalPoints.add(from);
            totalPoints.add(to);
            batchPoints.add(from);
            batchPoints.add(to);
        }
    }

    private void loadKmeans() {
        if (kmeans == null) {
            kmeans = new Kmeans(17);
        }
        kmeans.execute(totalPoints);
    }

    private void loadIKmeans() {
        if (iKmeans == null) {
            iKmeans = new IKmeans(17);
            iKmeans.setDataSet(batchPoints);
            iKmeans.updateK();
            if (iKmeans.getDataSetLength() != 0) {
                iKmeans.init();
            }
        }
        iKmeans.execute(batchPoints);
    }

    private void loadHGC() {
        clustering.load(batchPoints);
    }

    private static Calendar getCalendar(String date) {
        Calendar calendar = Calendar.getInstance();
        try {
            calendar.setTime(PropertiesUtil.dateFormat.parse(date));
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return calendar;
    }

    private void bindFields(String end) {
        dataNode.put("date", end);
        Calendar endCalendar = getCalendar(end);
        if (!endCalendar.before(PropertiesUtil.lastDateCalender)) {
            System.out.println(finished + end);
            dataNode.put("flag", finished);
        } else {
            System.out.println(unfinished + end);
            dataNode.put("flag", unfinished);
        }
    }

    private void drawPoints() {
        int pointsCnt;
        int clustersCnt;
        ArrayNode arrayNode = objectMapper.createArrayNode();

        if (parser.getClustering() == 0) {
            pointsCnt = totalPoints.size();
            clustersCnt = totalPoints.size();
            for (Point point : totalPoints) {
                arrayNode.addObject().put("size", 1).putArray("coordinates").add(point.getX()).add(point.getY());
            }
        } else {
            if (parser.getClusteringAlgorithm() == 0) {
                ArrayList<Cluster> points = this.clustering.getClusters(new double[]{parser.getLowerLongitude(), parser.getLowerLatitude(), parser.getUpperLongitude(), parser.getUpperLatitude()}, 18);
                ArrayList<Cluster> clusters = this.clustering.getClusters(new double[]{parser.getLowerLongitude(), parser.getLowerLatitude(), parser.getUpperLongitude(), parser.getUpperLatitude()}, parser.getZoom());
                pointsCnt = points.size();
                clustersCnt = clusters.size();
                for (Cluster cluster : clusters) {
                    ObjectNode objectNode = objectMapper.createObjectNode();
                    objectNode.putArray("coordinates").add(Clustering.xLng(cluster.getX())).add(Clustering.yLat(cluster.getY()));
                    objectNode.put("size", cluster.getNumPoints());
                    arrayNode.add(objectNode);
                }
            } else if (parser.getClusteringAlgorithm() == 1) {
                pointsCnt = iKmeans.getPointsCnt();
                clustersCnt = iKmeans.getK();
                arrayNode = iKmeans.getClustersJson();
            } else {
                pointsCnt = kmeans.getDataSetLength();
                clustersCnt = kmeans.getK();
                arrayNode = kmeans.getClustersJson();
            }
        }
        dataNode.put("data", arrayNode.toString());
        dataNode.put("repliesCnt", getTotalEdgesSize());
        dataNode.put("pointsCnt", pointsCnt);
        dataNode.put("clustersCnt", clustersCnt);
    }

    private void drawEdges() {
        HashMap<Edge, Integer> edges = new HashMap<>();
        if (parser.getClustering() == 0) {
            edges = totalEdges;
        } else {
            if (parser.getClusteringAlgorithm() == 0) {
                HashSet<Edge> externalEdgeSet = new HashSet<>();
                HashSet<Cluster> externalCluster = new HashSet<>();
                HashSet<Cluster> internalCluster = new HashSet<>();
                generateEdgeSet(edges, externalEdgeSet, externalCluster, internalCluster);
                TreeCut treeCutInstance = new TreeCut();
                if (parser.getTreeCutting() == 1) {
                    treeCutInstance.execute(this.clustering, parser.getLowerLongitude(), parser.getUpperLongitude(), parser.getLowerLatitude(), parser.getUpperLatitude(), parser.getZoom(), edges, externalEdgeSet, externalCluster, internalCluster);
                }
            } else if (parser.getClusteringAlgorithm() == 1) {
                edges = getKmeansEdges(iKmeans.getParents(), iKmeans.getCenters());
            } else {
                edges = getKmeansEdges(kmeans.getParents(), kmeans.getCenters());
            }
        }
        dataNode.put("edgesCnt", edges.size());
        if (parser.getBundling() == 0) {
            noBundling(edges);
        } else {
            runFDEB(edges);
        }
        dataNode.put("repliesCnt", getTotalEdgesSize());
    }

    private int getTotalEdgesSize() {
        int tot = 0;
        for (Edge edge : totalEdges.keySet()) {
            tot += totalEdges.get(edge);
        }
        return tot;
    }

    /**
     * prepares external egde set for tree cut.
     *
     * @param edges           the returning edge set, if tree cut is enabled, it contains only the internal edges.
     *                        Otherwise, it contains all the edges.
     * @param externalEdgeSet the returning external edge set
     * @param externalCluster outside cluster corresponding to edge set with only one node inside screen
     * @param internalCluster inside screen clusters
     */
    private void generateEdgeSet(HashMap<Edge, Integer> edges, HashSet<Edge> externalEdgeSet,
                                 HashSet<Cluster> externalCluster, HashSet<Cluster> internalCluster) {
        for (Edge edge : totalEdges.keySet()) {
            Cluster fromCluster = clustering.parentCluster(new Cluster(Clustering.lngX(edge.getFromX()), Clustering.latY(edge.getFromY())), parser.getZoom());
            Cluster toCluster = clustering.parentCluster(new Cluster(Clustering.lngX(edge.getToX()), Clustering.latY(edge.getToY())), parser.getZoom());
            double fromLongitude = Clustering.xLng(fromCluster.getX());
            double fromLatitude = Clustering.yLat(fromCluster.getY());
            double toLongitude = Clustering.xLng(toCluster.getX());
            double toLatitude = Clustering.yLat(toCluster.getY());
            boolean fromWithinRange = parser.getLowerLongitude() <= fromLongitude && fromLongitude <= parser.getUpperLongitude()
                    && parser.getLowerLatitude() <= fromLatitude && fromLatitude <= parser.getUpperLatitude();
            boolean toWithinRange = parser.getLowerLongitude() <= toLongitude && toLongitude <= parser.getUpperLongitude()
                    && parser.getLowerLatitude() <= toLatitude && toLatitude <= parser.getUpperLatitude();
            Edge e = new Edge(new Point(fromLongitude, fromLatitude), new Point(toLongitude, toLatitude));
            if (fromWithinRange && toWithinRange) {
                putEdgeIntoMap(edges, e, totalEdges.get(edge));
                internalCluster.add(fromCluster);
                internalCluster.add(toCluster);
            } else if (fromWithinRange || toWithinRange) {
                if (parser.getTreeCutting() == 0) {
                    putEdgeIntoMap(edges, e, totalEdges.get(edge));
                } else {
                    if (fromWithinRange) {
                        externalCluster.add(toCluster);
                    } else {
                        externalCluster.add(fromCluster);
                    }
                    externalEdgeSet.add(edge);
                }

            }
        }
    }

    private void putEdgeIntoMap(HashMap<Edge, Integer> edges, Edge edge, int weight) {
        if (edges.containsKey(edge)) {
            edges.put(edge, edges.get(edge) + weight);
        } else {
            edges.put(edge, weight);
        }
    }

    private HashMap<Edge, Integer> getKmeansEdges(HashMap<models.Point, Integer> parents, List<Point> center) {
        HashMap<Edge, Integer> edges = new HashMap<>();
        for (Edge edge : totalEdges.keySet()) {
            models.Point fromPoint = new models.Point(edge.getFromX(), edge.getFromY());
            models.Point toPoint = new models.Point(edge.getToX(), edge.getToY());
            int fromCluster = parents.get(fromPoint);
            int toCluster = parents.get(toPoint);
            Edge e = new Edge(center.get(fromCluster), center.get(toCluster));
            putEdgeIntoMap(edges, e, totalEdges.get(edge));
        }
        return edges;
    }

    private void runFDEB(HashMap<Edge, Integer> edges) {
        ArrayList<Edge> dataEdges = new ArrayList<>();
        ArrayList<Integer> closeEdgeList = new ArrayList<>();
        for (Map.Entry<Edge, Integer> entry : edges.entrySet()) {
            Edge edge = entry.getKey();
            if (Math.pow(edge.length(), 2) <= 0.001)
                continue;
            dataEdges.add(edge);
            closeEdgeList.add(entry.getValue());
        }
        ForceBundling forceBundling = new ForceBundling(dataEdges);
        forceBundling.setS(parser.getZoom());
        ArrayList<Path> pathResult = forceBundling.forceBundle();
        int isolatedEdgesCnt = forceBundling.getIsolatedEdgesCnt();
        ArrayNode pathJson = objectMapper.createArrayNode();
        for (int i = 0; i < pathResult.size(); i++) {
            Path path = pathResult.get(i);
            for (int j = 0; j < path.getPath().size() - 1; j++) {
                ObjectNode lineNode = objectMapper.createObjectNode();
                lineNode.putArray("from").add(path.getPath().get(j).getX()).add(path.getPath().get(j).getY());
                lineNode.putArray("to").add(path.getPath().get(j + 1).getX()).add(path.getPath().get(j + 1).getY());
                lineNode.put("width", closeEdgeList.get(i));
                pathJson.add(lineNode);
            }
        }
        dataNode.put("data", pathJson.toString());
        dataNode.put("isolatedEdgesCnt", isolatedEdgesCnt);
    }

    private void noBundling(HashMap<Edge, Integer> edges) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        for (Map.Entry<Edge, Integer> entry : edges.entrySet()) {
            ObjectNode lineNode = objectMapper.createObjectNode();
            lineNode.putArray("from").add(entry.getKey().getFromX()).add(entry.getKey().getFromY());
            lineNode.putArray("to").add(entry.getKey().getToX()).add(entry.getKey().getToY());
            lineNode.put("width", entry.getValue());
            arrayNode.add(lineNode);
        }
        dataNode.put("data", arrayNode.toString());
        dataNode.put("isolatedEdgesCnt", edges.size());
    }
}
