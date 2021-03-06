package data_model.time_expanded.street_network;

import java.awt.datatransfer.StringSelection;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.openstreetmap.osmosis.core.domain.v0_6.CommonEntityData;
import org.openstreetmap.osmosis.core.domain.v0_6.OsmUser;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.postgis.LineString;
import org.postgis.PGgeometry;
import org.postgis.Point;

import crosby.binary.BinaryParser;
import crosby.binary.Osmformat;
import crosby.binary.Osmformat.DenseNodes;
import crosby.binary.Osmformat.HeaderBlock;
import crosby.binary.Osmformat.Node;
import crosby.binary.Osmformat.Relation;
import crosby.binary.file.BlockInputStream;
import crosby.binary.file.BlockReaderAdapter;
import data_model.database.DBConnector;
import data_model.time_expanded.street_network.components.DenseInfo;
import data_model.time_expanded.street_network.components.DenseNode;
import data_model.time_expanded.street_network.components.Edge;
import data_model.time_expanded.street_network.components.RealNode;

public class GraphBuilder extends BinaryParser{
	
	/** The magic number used to indicate no version number metadata for this entity. */
    static final int NOVERSION = -1;
    /** The magic number used to indicate no changeset metadata for this entity. */
    static final int NOCHANGESET = -1;
	private HashMap<Long, DenseNode> denseNodes;
	private HashMap<Long, RealNode> realNodes;
	private HashMap<Long, Long> insertedNodes;
	private HashMap<Long, Way> ways;
	private ArrayList<Edge> edges;
	private String outPath;
	private String city;
	private DBConnector db;
	private String file;
	public static final Date NODATE = new Date(-1);
	public int nrNodes = 0;
	public int nrEdges = 0;
	private boolean newNodes = false;
	
//	public GraphBuilder(HashMap<Long, DenseNode> allNodes,  HashMap<Long, org.openstreetmap.osmosis.core.domain.v0_6.Way> allWays, String outPath, String city, DBConnector conn){
//		denseNodes = allNodes;
//		realNodes = new HashMap<Long, RealNode>();
//		ways = allWays;
//		this.outPath = outPath;
//		this.city = city;
//		db = conn;
//	}
	
	public GraphBuilder(String file, String outPath, String city, DBConnector conn){
		this.file = file;
		denseNodes = new HashMap<Long, DenseNode>();
		realNodes = new HashMap<Long, RealNode>();
		insertedNodes = new HashMap<Long, Long>();
		ways = new HashMap<Long, Way>();
		edges = new ArrayList<Edge>();
		this.outPath = outPath;
		this.city = city;
		db = conn;
		waysBlocks = new ArrayList<PBFBlock>();
	}
    
  //checks if two way intersect by finding the same node in the lists of nodes describing the ways
    public long intersect(List<Long> outer, List<Long> inner){
    	for(Long l : outer)
    		if(l > 0 && String.valueOf(l).length() >= 7)
    			for(Long l1: inner)
	    			if(l1 > 0 && String.valueOf(l1).length() >= 7 && l1.equals(l))
	    				return l1;
    	return -1;
    }
    
//    public void parseNetwork(){
//    	for(PBFBlock block : getWaysBlocks()){
//    		parseWays(block.getWays());
//    		getRealStreetNodes();
//    	}
//    }
    
    /**
     * 
     * @return The real node representing crosses in the street network. The nodes between each couple of source-destination node are discarded in this phase.
     */
    public Collection<RealNode> getRealStreetNodes(){
//    	System.out.println("[INFO] Real Nodes.");
//    	realNodes = new HashMap<Long, RealNode>();
//    	System.out.println("Nodes: " + nodes);
//    	parseWays(getWaysBlocks());
//    	List<Long> discovered = new ArrayList<Long>();
    	for(Way way : ways.values()){
    		//getting source and destination of the way, which are real nodes
    		Long source = way.getWayNodes().get(0).getNodeId();
    		Long destination = way.getWayNodes().get(way.getWayNodes().size()-1).getNodeId();
    		
    		//add the source to the list if it is not there yet
    		if(realNodes.get(source) == null && insertedNodes.get(source) == null){
	    		DenseNode dn = denseNodes.get(source);
	    		Point p = new Point(dn.getdInfo().getLognitude(),dn.getdInfo().getLatitude());
	    		p.setSrid(4326);
	    		RealNode aNode = new RealNode(source, new PGgeometry(p));
	    		realNodes.put(source, aNode);
	    		insertedNodes.put(source, source);
//	    		discovered.add(dn.getId());
	    		nrNodes++;
    		}
    		
    		//same for the destination
    		if(realNodes.get(destination) == null && insertedNodes.get(destination) == null){
    			DenseNode dn = denseNodes.get(destination);
	    		Point p = new Point(dn.getdInfo().getLognitude(),dn.getdInfo().getLatitude());
	    		p.setSrid(4326);
	    		RealNode aNode = new RealNode(destination, new PGgeometry(p));
	    		realNodes.put(destination, aNode);
	    		insertedNodes.put(destination, destination);
//	    		discovered.add(dn.getId());
	    		nrNodes++;
    		}
    	}
    	
//    	System.out.println("[INFO] Real nodes: " + realNodes.values().size());
//    	removeDiscoveredNodes(discovered);
    	Collection<RealNode> result = realNodes.values();
//    	denseNodes = new HashMap<Long, DenseNode>();
    	buildNodes();
    	realNodes = new HashMap<Long, RealNode>();
    	return result; 
    }
    
    public void removeDiscoveredNodes(List<Long> nodes){
    	for(Long l : nodes)
    		denseNodes.remove(l);
    }
    
    public void populateScript(){
    	if(newNodes){
    		buildNodes();
    	}
    	buildEdges();
    }
    
    public HashMap<Long, RealNode> buildNodes(){
//    	HashMap<Long, RealNode> realNodes = new HashMap<Long, RealNode>();
//    	Collection<RealNode> nodes = this.realNodes.values();
//		System.out.println("[INFO] Nodes: " + nodes.size());
    	System.out.print("[INFO] Bulding real nodes...");
    	ArrayList<RealNode> tmp = new ArrayList<RealNode>();
//    	db.resetFile(outPath + "/" + city + "_street_nodes_import.sql");
		db.openWriter(outPath + "/" + city + "_street_nodes_import.sql", true);
//		db.deleteClause(city + "_street_nodes");
//		db.resetCheckpoint();
		int counter = 0;
		for(RealNode rn : realNodes.values()){
//			if(counter == 999){
//				counter = 0;
//				insertNodes(tmp);
//				tmp = new ArrayList<RealNode>();
//			}
//			realNodes.put(rn.getId(), rn);
			tmp.add(rn);
//			nrNodes++;
//			counter++;
		}
//		if(counter != 0)
			insertNodes(tmp);
		db.closeWriter();
		System.out.println("Done.");
		return this.realNodes;
	}
    
    public void insertNodes(Collection<RealNode> nodes){
    	db.insertMultipleStreetNodes(nodes, city);
    }
    
    public ArrayList<Edge> buildEdges(){
    	System.out.print("[INFO] Building edges...");
//    	HashMap<Long, Edge> edges = new HashMap<Long, Edge>();
    	ArrayList<Edge> tmp = new ArrayList<Edge>();
//    	db.resetFile(outPath + "/" + city + "_street_edges_import.sql");
    	db.openWriter(outPath + "/" + city + "_street_edges_import.sql", true);
//    	db.deleteClause(city + "_street_edges");
    	db.resetCheckpoint();
    	int counter = 0;
    	for(Edge e : edges){
//    		if(counter == 999){
//    			insertEdges(tmp);
//    			counter = 0;
//    			tmp = new ArrayList<Edge>();
//    		}
    		tmp.add(e);
//    		nrEdges++;
//    		counter += 2;
    	}
//    	for(Long l : ways.keySet()){
//    		if(counter == 999){
//    			insertEdges(edges);
//    			edges = new ArrayList<Edge>();
//    		}
//    		Way way = ways.get(l);
//    		List<WayNode> tmp = way.getWayNodes();
//    		Edge e = new Edge(l, tmp.get(0).getNodeId(), (tmp.get(way.getWayNodes().size()-1).getNodeId()), buildEdgeGeometry(tmp), 0);
//    		edges.add(e);
//    		Collections.reverse(tmp);
//    		e = new Edge(l, tmp.get(0).getNodeId(), (tmp.get(way.getWayNodes().size()-1).getNodeId()), buildEdgeGeometry(tmp), 0);
//    		edges.add(e);
////    		edges.put(destination, reverseEdge(edgePoint, destination, source));
//    		counter += 2;
//    	}
//    	if(counter != 0)
    		insertEdges(tmp);
    	db.closeWriter();
    	System.out.println("Done.");
    	return edges;
    }
    
    public void addEdges(Set<Long> waysKeys){
    	for(Long l : waysKeys){
    		Way way = ways.get(l);
    		List<WayNode> tmp = way.getWayNodes();
    		Edge e = new Edge(l, tmp.get(0).getNodeId(), (tmp.get(way.getWayNodes().size()-1).getNodeId()), buildEdgeGeometry(tmp), 0);
    		edges.add(e);
    		Collections.reverse(tmp);
    		e = new Edge(l, tmp.get(0).getNodeId(), (tmp.get(way.getWayNodes().size()-1).getNodeId()), buildEdgeGeometry(tmp), 0);
    		edges.add(e);
    	}
    	nrEdges += edges.size();
    }
    
    public Edge reverseEdge(ArrayList<Point> points, long id, long source, long destination){
    	Collections.reverse(points);
    	PGgeometry edge = new PGgeometry(new LineString(points.toArray(new Point[] {})));
    	return new Edge(id, source, destination, edge, 0);
    }
    
    public PGgeometry buildEdgeGeometry(List<org.openstreetmap.osmosis.core.domain.v0_6.WayNode> nodes){
    	ArrayList<Point> points = new ArrayList<Point>();
    	for(org.openstreetmap.osmosis.core.domain.v0_6.WayNode wn : nodes){
    		DenseNode aNode = denseNodes.get(wn.getNodeId());
    		if(aNode != null){
	    		Point p = new Point(aNode.getdInfo().getLognitude(), aNode.getdInfo().getLatitude());
	    		points.add(p);
    		}
    	}
    	return new PGgeometry(new LineString(points.toArray(new Point[]{})));
    }
    
    public void insertEdges(Collection<Edge>edges){
    	db.insertMultipleStreetEdges(edges, city);
    }

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}
	
	public void parsePBF(){
		InputStream input;
		try {
			input = new FileInputStream(file);
			BlockReaderAdapter brad = this;
			new BlockInputStream(input, brad).process();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	   /** Get the osmosis object representing a the user in a given Info protobuf.
     * @param info The info protobuf.
     * @return The OsmUser object */
    OsmUser getUser(Osmformat.Info info) {
        // System.out.println(info);
        if (info.hasUid() && info.hasUserSid()) {
            if (info.getUid() < 0) {
              return OsmUser.NONE;
            }
            return new OsmUser(info.getUid(), getStringById(info.getUserSid()));
        } else {
            return OsmUser.NONE;
        }
    }

    @Override
    protected void parseRelations(List<Relation> rels) {
        if (!rels.isEmpty())
            System.out.println("[INFO] Got some relations to parse.");
//            for(Relation rel : rels)
//            	System.out.println("Relation, %e, ", rel.getId(), rel.get);
    }

    @Override
    protected void parseDense(DenseNodes nodes) {
    	System.out.print("[INFO] Parsing dense nodes...");
    	newNodes = true;
//    	denseNodes = new HashMap<Long, DenseNode>();
        long lastId=0;
        long lastLat=0;
        long lastLon=0;

        for (int i=0 ; i<nodes.getIdCount() ; i++) {
            lastId += nodes.getId(i);
            lastLat += nodes.getLat(i);
            lastLon += nodes.getLon(i);
            DenseInfo di = new DenseInfo(nodes.getDenseinfo().getVersion(i), nodes.getDenseinfo().getTimestamp(i), nodes.getDenseinfo().getChangeset(i), parseLat(lastLat), parseLon(lastLon));
            DenseNode dn = new DenseNode(lastId, di);
            denseNodes.put(lastId, dn);
        }
        System.out.println("Done.");
    }

    @Override
    protected void parseNodes(List<Node> nodes) {
        for (Node n : nodes) {
            System.out.printf("[INFO] Regular node, ID %d @ %.6f,%.6f\n",
                    n.getId(),parseLat(n.getLat()),parseLon(n.getLon()));
        }
    }

    @Override
    protected void parseWays(List<Osmformat.Way> ways) {
    	this.ways = new HashMap<Long, Way>();
    	edges = new ArrayList<Edge>();
    	System.out.print("[INFO] Parsing ways...");
    	boolean street = false;
    	
    	 for (Osmformat.Way i : ways) {
    		 street = false;
             List<Tag> tags = new ArrayList<Tag>();
             for (int j = 0; j < i.getKeysCount(); j++) {
            	 String tag = getStringById(i.getKeys(j));
            	 Tag t = new Tag(tag, getStringById(i.getVals(j)));
                 tags.add(t);
                 if(tag.equals("highway"))
                	 street = true;
             }
             
             //check if the current way is a street 
             if(!street)
            	 continue;
                 
             long lastId = 0;
             List<WayNode> nodes = new ArrayList<WayNode>();
             for (long j : i.getRefsList()) {
                 nodes.add(new WayNode(j + lastId));
                 lastId = j + lastId;
             }

             long id = i.getId();

             // long id, int version, Date timestamp, OsmUser user,
             // long changesetId, Collection<Tag> tags,
             // List<WayNode> wayNodes
             Way tmp;
             if (i.hasInfo()) {
                 Osmformat.Info info = i.getInfo();
                 tmp = new Way(new CommonEntityData(id, info.getVersion(), getDate(info),
                		 OsmUser.NONE, info.getChangeset(), tags), nodes);
             } else {
            	 tmp = new Way(new CommonEntityData(id, NOVERSION, NODATE,
            			 OsmUser.NONE, NOCHANGESET, tags), nodes);

             }
             
             this.ways.put(id, tmp);             
        }
    	System.out.println("Done.");
    	if(newNodes)
    		getRealStreetNodes();
        addEdges(this.ways.keySet());
        buildEdges();
//        populateScript();
//        denseNodes = null;
    }

    @Override
    protected void parse(HeaderBlock header) {
        System.out.println("[INFO] Got header block.");
    }

    public void complete() {
        System.out.println("[INFO] Complete!");
    }
    
    public HashMap<Long, DenseNode> getAllNodes(){
    	return denseNodes;
    }
    
	public  HashMap<Long, org.openstreetmap.osmosis.core.domain.v0_6.Way> getAllWays(){
		return ways;
	}
}
