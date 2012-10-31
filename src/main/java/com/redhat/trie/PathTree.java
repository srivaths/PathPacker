/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */

package com.redhat.trie;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.StringTokenizer;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.DataFormatException;

/**
 * PathTree
 *
 * An efficient means by which to check the content sets.
 *
 * TODO - this is a prototype stub
 */
public class PathTree {
    private List<HuffNode> nodeDictionary;
    private List<HuffNode> pathDictionary;
    private StringBuffer nodeBits; // TODO make a smart getter for this

    private byte[] payload;
    
    /**
     * context incrementor used when building the trees
     */
    private NodeContext pathNodeContext;

    /**
     * context incrementor used when building the trees
     */
    private NodeContext huffNodeContext;

    /**
     * Length of bits read from initial Inflater stream of the payload.
     * Also, this is the offset in the payload.
     *
     */
    private long dictOffset;

    /** 
     * storage for the count of nodes in the packed tree.
     */
    private int nodeCount;

    /**
     * toggled when either setContentSets or setPayload has been run
     */
    private boolean modified;

    /**
     * Naked Constructor.
     *
     * Expecting to then run setPayload() or setContentSets() next.
     */
    public PathTree() {
    }

    /**
     * Constructor using the compressed byte array payload.
     */
    public PathTree(byte[] payload) {
        setPayload(payload);
    }

    /**
     * Constructor using the list of content sets.
     *
     * FIXME - This is a stub.
     */
    public PathTree(List<String> contentSets) throws PayloadException {
        try {
            setContentSets(contentSets);
        } catch (PayloadException ex) {
            throw ex;
        }
    }

    /**
     * set the compressed payload for this PathTree.
     *
     * See also setContentSets()
     *
     * This re-initializes this object.
     */
    public void setPayload(byte[] payload) {
        this.modified = true;
        setNodeBits(null);
        setNodeCount(0);

        this.pathNodeContext = new NodeContext();
        this.huffNodeContext = new NodeContext();

        this.payload = payload;

        //inflatePathDict

        this.modified = false;
    }

    private NodeContext getPathNodeContext() {
        return this.pathNodeContext;
    }

    private NodeContext getHuffNodeContext() {
        return this.huffNodeContext;
    }

    private long getDictOffset() {
        return this.dictOffset;
    }

    private int getNodeCount() {
        return this.nodeCount;
    }

    /**
     * getter for the compressed payload blob.
     *
     * @return      byte array of deflated dict and tree.
     */
    public byte[] getPayload() {
        return this.payload;
    }

    /**
     * the buffer of significant bits, with regard to how many nodes there are.
     * 
     * @return      StringBuffer of 
     */
    private StringBuffer getNodeBits() {
        return this.nodeBits;
    }

    private void setNodeBits(StringBuffer nodeBits) {
        this.nodeBits = nodeBits;
    }

    private void setDictOffset(long offset) {
        this.dictOffset = offset;
    }

    private void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    /**
     * get the PathNode dictionary. If it is not already built, then get it from the payload
     *
     * @return      List of HuffNode's, with the value set as a the PathNode object 
     * @throws PayloadException  if the relevant section of the payload is not readable
     */
    private List<HuffNode> getPathDictionary() throws PayloadException {
        if (this.modified || this.pathDictionary == null) {
            this.pathDictionary = new ArrayList<HuffNode>();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Inflater inf = new Inflater();
            InflaterOutputStream ios = new InflaterOutputStream(baos, inf);
            try {
                ios.write(getPayload());
                ios.finish();
            } catch (IOException ex) {
                throw new PayloadException();
            }
            setDictOffset(inf.getBytesRead());

            int weight = 1;
            for (String name : byteArrayToStringList(baos.toByteArray())) {
                this.pathDictionary.add(new HuffNode(getHuffNodeContext(), name, weight++));
            }
            this.pathDictionary.add(new HuffNode(HuffNode.END_NODE, weight));
        }
        return this.pathDictionary;
    }

    /**
     * This returns the list of weighted HuffNode from the packed nodes.
     * If the payload has been set, this should regenerate.
     *
     * @return  list of weighted HuffNode's
     * @throws  PayloadException     if the offsetted payload is not readable
     */
    private List<HuffNode> getNodeDictionary() throws PayloadException {
        if (this.pathDictionary == null) {
            getPathDictionary(); // this has to run before the nodeDictionary bits are ready
        }
        if (this.modified || this.pathDictionary == null || this.nodeDictionary == null) {
            this.nodeDictionary = new ArrayList<HuffNode>();
            setNodeBits(new StringBuffer());

            ByteArrayInputStream bais = new ByteArrayInputStream(getPayload(),
                (new Long(getDictOffset())).intValue(), (new Long(getPayload().length - getDictOffset()).intValue()));
            int value = bais.read();
            // check for size bits
            setNodeCount(value);
            if (value > 127) {
                byte[] count = new byte[value - 128];
                try {
                    bais.read(count);
                } catch (IOException ex) {
                    throw new PayloadException();
                }
                int total = 0;
                for (int k = 0; k < value - 128; k++) {
                    total = (total << 8) | (count[k] & 0xFF);
                }
                setNodeCount(total);
            }
            value = bais.read();
            while (value != -1) {
                String someBits = Integer.toString(value, 2);
                for (int pad = 0; pad < 8 - someBits.length(); pad++) {
                    this.nodeBits.append("0");
                }
                this.nodeBits.append(someBits);
                value = bais.read();
            }

            for (int j = 0; j < getNodeCount(); j++) {
                this.nodeDictionary.add(new HuffNode(new PathNode(getPathNodeContext()), j));
            }
        }
        return this.nodeDictionary;
    }

    /**
     * get the HuffNode trie of the path dictionary
     *
     * @return      the populated HuffNode trie of the PathNode dictionary
     * @throws  PayloadException     if the newly read PathNode dictionary can not be read from the payload
     */
    private HuffNode getPathTrie() throws PayloadException {
        try {
            return makeTrie(getPathDictionary());
        } catch (PayloadException ex) {
            throw ex;
        }
    }

    /**
     * get the HuffNode trie of the node dictionary
     *
     * @return      the populated HuffNode trie of the Node name dictionary
     * @throws  PayloadException     if the newly read Node name dictionary can not be read from the payload
     */
    private HuffNode getNodeTrie() throws PayloadException {
        try {
            return makeTrie(getNodeDictionary());
        } catch (PayloadException ex) {
            throw ex;
        }
    }

    /**
     * get the root PathNode, of the munged together nodes and dictionary
     */
    public PathNode getRootPathNode() throws PayloadException {
        // populate the PathNodes so we can rebuild the cool url tree
        Set<PathNode> pathNodes;
        try {
            pathNodes =  populatePathNodes(getNodeDictionary(),
                getPathTrie(), getNodeTrie(), getNodeBits());
        } catch (PayloadException ex) {
            throw ex;
        }
        // find the root, he has no parents
        PathNode root = null;
        for (PathNode pn : pathNodes) {
            if (pn.getParents().size() == 0) {
                root = pn;
                break;
            }
        }
        return root;
    }

    /**
     * TODO - this is a stub
     */
    public boolean validate(String contentPath) {
        return false;
    }

    /**
     * consume the list of content sets, and operate the same way.
     *
     * See also setPayload()
     *
     * This re-initializes this object.
     */
    public void setContentSets(List<String> contentSets) throws PayloadException {
        this.modified = true;
        this.nodeBits = null;
        setNodeCount(0);

        this.pathNodeContext = new NodeContext();
        this.huffNodeContext = new NodeContext();

        PathNode treeRoot = makePathTree(contentSets, new PathNode());
        List<String> nodeStrings = orderStrings(treeRoot);
        if (nodeStrings.size() == 0) {
            this.payload = new byte[0];
            return;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        List<HuffNode> stringHuffNodes = getStringNodeList(nodeStrings);
        HuffNode stringTrieParent = makeTrie(stringHuffNodes);
        try {
            data.write(byteProcess(nodeStrings));
        } catch (Throwable ex) {
            throw new PayloadException();
        }

        List<PathNode> orderedNodes = orderNodes(treeRoot);
        List<HuffNode> pathNodeHuffNodes = getPathNodeNodeList(orderedNodes);
        HuffNode pathNodeTrieParent = makeTrie(pathNodeHuffNodes);
        try {
            data.write(makeNodeDictionary(stringTrieParent,
                pathNodeTrieParent, orderedNodes));
        } catch (Throwable ex) {
            throw new PayloadException();
        }

        this.payload = data.toByteArray();

        this.modified = false;
    }

    /**
     * populate the parent PathNode, with the Strings in contents
     *
     * @param contents  a list of strings to be consumed
     * @param parent    a PathNode, will be the root node, to be populated
     * @return          is the same object as the parent param
     */
    public PathNode makePathTree(List<String> contents, PathNode parent) {
        PathNode endMarker = new PathNode(new NodeContext());
        for (String path : contents) {
            StringTokenizer st = new StringTokenizer(path, "/");
            makePathForURL(st, parent, endMarker);
        }
        condenseSubTreeNodes(endMarker);
        return parent;
    }

    private List<String> byteArrayToStringList(byte[] ba) {
        List<String> strings = new ArrayList<String>();
        String str = "";

        for (byte b : ba) {
            if (b == '\0') {
                strings.add(str);
                str = "";
            } else {
                str += (char) b;
            }
        }
        return strings;
    }

    /**
     * Make a HuffNode trie from a list of weighted HuffNodes
     *
     * @param: nodesList List of individual HuffNode, that have been properly weighted
     */
    private HuffNode makeTrie(List<HuffNode> nodesList) {
        List<HuffNode> trieNodesList = new ArrayList<HuffNode>();

        trieNodesList.addAll(nodesList);

        // drop the first node if path node value, it is not needed
        if (trieNodesList.get(0).getValue() instanceof PathNode) {
            trieNodesList.remove(0);
        }
        while (trieNodesList.size() > 1) {
            int node1 = findSmallest(-1, trieNodesList);
            int node2 = findSmallest(node1, trieNodesList);
            HuffNode hn1 = trieNodesList.get(node1);
            HuffNode hn2 = trieNodesList.get(node2);
            HuffNode merged = mergeNodes(hn1, hn2);
            trieNodesList.remove(hn1);
            trieNodesList.remove(hn2);
            trieNodesList.add(merged);
        }
        return trieNodesList.get(0);
    }

    /**
     * build out the path nodes with their weighted position
     *
     * @return  the Set of weighted PathNode
     */
    private Set<PathNode> populatePathNodes(List<HuffNode> nodeDictionary,
        HuffNode pathTrie, HuffNode nodeTrie, StringBuffer nodeBits) {
        Set<PathNode> pathNodes = new HashSet<PathNode>();
        for (HuffNode node : nodeDictionary) {
            pathNodes.add((PathNode) node.getValue());
            boolean stillNode = true;
            while (stillNode) {
                // get first child name
                // if its HuffNode.END_NODE we are done
                String nameValue = null;
                StringBuffer nameBits = new StringBuffer();
                while (nameValue == null && stillNode) {
                    nameBits.append(getNodeBits().charAt(0));
                    getNodeBits().deleteCharAt(0);
                    Object lookupValue = findHuffNodeValueByBits(pathTrie,
                        nameBits.toString());
                    if (lookupValue != null) {
                        if (lookupValue.equals(HuffNode.END_NODE)) {
                            stillNode = false;
                            break;
                        }
                        nameValue = (String) lookupValue;
                    }
                    if (getNodeBits().length() == 0) {
                        stillNode = false;
                    }
                }

                PathNode nodeValue = null;
                StringBuffer pathBits = new StringBuffer();
                while (nodeValue == null && stillNode) {
                    pathBits.append(getNodeBits().charAt(0));
                    getNodeBits().deleteCharAt(0);
                    PathNode lookupValue = (PathNode) findHuffNodeValueByBits(nodeTrie,
                        pathBits.toString());
                    if (lookupValue != null) {
                        nodeValue = lookupValue;
                        nodeValue.addParent((PathNode) node.getValue());
                        ((PathNode) node.getValue()).addChild(
                            new NodePair(nameValue, nodeValue));
                    }
                    if (getNodeBits().length() == 0) {
                        stillNode = false;
                    }
                }
            }
        }
        return pathNodes;
    }

    /**
     * Return the list of all the content sets in the packed payload
     *
     * @return   all the content sets! (unless there was a PayloadException, then empty list)
     */
    public List<String> toList() {
        List<String> urls = new ArrayList<String>();
        StringBuffer aPath = new StringBuffer();
        try {
            makeURLs(getRootPathNode(), urls, aPath);
        } catch (PayloadException ex) {
            // swallow it, I guess. return empty list
        }
        return urls;
    }

    private void makeURLs(PathNode root, List<String> urls, StringBuffer aPath) {
        if (root.getChildren().size() == 0) {
            urls.add(aPath.toString());
        }
        for (NodePair child : root.getChildren()) {
            StringBuffer childPath = new StringBuffer(aPath.substring(0));
            childPath.append("/");
            childPath.append(child.getName());
            makeURLs(child.getConnection(), urls, childPath);
        }
    }

    private Object findHuffNodeValueByBits(HuffNode trie, String bits) {
        HuffNode left = trie.getLeft();
        HuffNode right = trie.getRight();

        if (bits.length() == 0) {
            return trie.getValue();
        }

        char bit = bits.charAt(0);
        if (bit == '0') {
            if (left == null) { throw new RuntimeException("Encoded path not in trie"); }
            return findHuffNodeValueByBits(left, bits.substring(1));
        }
        else if (bit == '1') {
            if (right == null) { throw new RuntimeException("Encoded path not in trie"); }
            return findHuffNodeValueByBits(right, bits.substring(1));
        }
        return null;
    }

    private int findSmallest(int exclude, List<HuffNode> nodes) {
        int smallest = -1;
        for (int index = 0; index < nodes.size(); index++) {
            if (index == exclude) {
                continue;
            }
            if (smallest == -1 || nodes.get(index).getWeight() <
                nodes.get(smallest).getWeight()) {
                smallest = index;
            }
        }
        return smallest;
    }

    private HuffNode mergeNodes(HuffNode node1, HuffNode node2) {
        HuffNode left = node1;
        HuffNode right = node2;
        HuffNode parent = new HuffNode(getHuffNodeContext(),
                null, left.getWeight() + right.getWeight(), left, right);
        return parent;
    }
    
    /**
     * 
     */
    private List<HuffNode> getStringNodeList(List<String> pathStrings) {
        List<HuffNode> nodes = new ArrayList<HuffNode>();
        int idx = 1;
        for (String part : pathStrings) {
            nodes.add(new HuffNode(getHuffNodeContext(), part, idx++));
        }
        nodes.add(new HuffNode(HuffNode.END_NODE, idx));
        return nodes;
    }

    /**
     * 
     */
    private List<HuffNode> getPathNodeNodeList(List<PathNode> pathNodes) {
        List<HuffNode> nodes = new ArrayList<HuffNode>();
        int idx = 0;
        for (PathNode pn : pathNodes) {
            nodes.add(new HuffNode(getHuffNodeContext(), pn, idx++));
        }
        return nodes;
    }

    /**
     * write word entries to a deflated byte array.
     * 
     * @param entries   list of words (presumably the words in the PathTree dictionary
     * @return          deflated byte array
     */
    private byte[] byteProcess(List<String> entries)
        throws IOException, UnsupportedEncodingException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(baos,
            new Deflater(Deflater.BEST_COMPRESSION));
        for (String segment : entries) {
            dos.write(segment.getBytes("UTF-8"));
            dos.write("\0".getBytes("UTF-8"));
        }
        dos.finish();
        dos.close();
        return baos.toByteArray();
    }

    private List<String> orderStrings(PathNode parent) {
        List<String> parts = new ArrayList<String>();
        // walk tree to make string map
        Map<String, Integer> segments =  new HashMap<String, Integer>();
        Set<PathNode> nodes =  new HashSet<PathNode>();
        buildSegments(segments, nodes, parent);
        for (String part : segments.keySet()) {
            if (!part.equals("")) {
                int count = segments.get(part);
                if (parts.size() == 0) {
                    parts.add(part);
                }
                else {
                    int pos = parts.size();
                    for (int i = 0; i < parts.size(); i++) {
                        if (count < segments.get(parts.get(i))) {
                            pos = i;
                            break;
                        }
                    }
                    parts.add(pos, part);
                }
            }
        }
        return parts;
    }

    private byte[] makeNodeDictionary(HuffNode stringParent,
        HuffNode pathNodeParent, List<PathNode> pathNodes) 
        throws PayloadException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int nodeSize = pathNodes.size();
        if (nodeSize > 127) {
            ByteArrayOutputStream countBaos = new ByteArrayOutputStream();
            boolean start = false;
            /* TODO ??? */
            for (byte b : toByteArray(nodeSize)) {
                if (b == 0 && !start) {
                    continue;
                }
                else {
                    countBaos.write(b);
                    start = true;
                }
            }
            baos.write(128 + countBaos.size());
            try {
                countBaos.close();
                baos.write(countBaos.toByteArray());
            } catch (Throwable ex) {
                throw new PayloadException();
            }
        }
        else {
            baos.write(nodeSize);
        }
        StringBuffer bits = new StringBuffer();
        String endNodeLocation = findHuffPath(stringParent, HuffNode.END_NODE);
        for (PathNode pn : pathNodes) {
            for (NodePair np : pn.getChildren()) {
                bits.append(findHuffPath(stringParent, np.getName()));
                bits.append(findHuffPath(pathNodeParent, np.getConnection()));
            }
            bits.append(endNodeLocation);
            while (bits.length() >= 8) {
                int next = 0;
                for (int i = 0; i < 8; i++) {
                    next = (byte) next << 1;
                    if (bits.charAt(i) == '1') {
                        next++;
                    }
                }
                baos.write(next);
                bits.delete(0, 8);
            }
        }

        if (bits.length() > 0) {
            int next = 0;
            for (int i = 0;  i < 8; i++) {
                next = (byte) next << 1;
                if (i < bits.length() && bits.charAt(i) == '1') {
                    next++;
                }
            }
            baos.write(next);
        }
        byte[] result = baos.toByteArray();
        try {
            baos.close();
        } catch (Throwable ex) {
            throw new PayloadException();
        }
        return result;
    }

    /**
     * Arrange the list of unique PathNodes, by size.
     *
     * @param treeRoot  a "root" PathNode, to get the list from
     * @return          a List of size ordered nodes
     */
    private List<PathNode> orderNodes(PathNode treeRoot) {
        List<PathNode> result = new ArrayList<PathNode>();

        // walk tree to make string map
        Set<PathNode> nodes =  getPathNodes(treeRoot);
        for (PathNode pn : nodes) {
            int count = pn.getParents().size();
            if (nodes.size() == 0) {
                nodes.add(pn);
            }
            else {
                int pos = result.size();
                for (int i = 0; i < result.size(); i++) {
                    if (count <= result.get(i).getParents().size()) {
                        pos = i;
                        break;
                    }
                }
                result.add(pos, pn);
            }
        }
        return result;
    }

    /**
     * return the unique set of PathNodes in a given treeRoot.
     *
     * @param treeRoot  a "root" PathNode. Which can all be a matter of perspective.
     * @return          the unique Set of Nodes
     */
    private Set<PathNode> getPathNodes(PathNode treeRoot) {
        Set<PathNode> nodes = new HashSet<PathNode>();
        nodes.add(treeRoot);
        for (NodePair np : treeRoot.getChildren()) {
            nodes.addAll(getPathNodes(np.getConnection()));
        }
        return nodes;
    }

    private String findHuffPath(HuffNode trie, Object need) {
        HuffNode left = trie.getLeft();
        HuffNode right = trie.getRight();
        if (left != null && left.getValue() != null) {
            if (need.equals(left.getValue())) {
                return "0";
            }
        }
        if (right != null && right.getValue() != null) {
            if (need.equals(right.getValue())) {
                return "1";
            }
        }
        if (left != null) {
            String leftPath = findHuffPath(left, need);
            if (leftPath.length() > 0) {
                return "0" + leftPath;
            }
        }
        if (right != null) {
            String rightPath = findHuffPath(right, need);
            if (rightPath.length() > 0) {
                return "1" + rightPath;
            }
        }
        return "";
    }

    /**
     * given a tokenized URL path, build out the PathNode parent,
     * and append endMarker to terminal nodes.
     */
    private void makePathForURL(StringTokenizer st, PathNode parent, PathNode endMarker) {
        if (st.hasMoreTokens()) {
            String childVal = st.nextToken();
            if (childVal.equals("")) {
                return;
            }

            boolean isNew = true;
            for (NodePair child : parent.getChildren()) {
                if (child.getName().equals(childVal) &&
                        !child.getConnection().equals(endMarker)) {
                    makePathForURL(st, child.getConnection(), endMarker);
                    isNew = false;
                }
            }
            if (isNew) {
                PathNode next = null;
                if (st.hasMoreTokens()) {
                    next = new PathNode(parent.getContext());
                    parent.addChild(new NodePair(childVal, next));
                    next.addParent(parent);
                    makePathForURL(st, next, endMarker);
                } else {
                    parent.addChild(new NodePair(childVal, endMarker));
                    if (!endMarker.getParents().contains(parent)) {
                        endMarker.addParent(parent);
                    }
                }
            }
        }
    }

    private void buildSegments(Map<String, Integer> segments,
        Set<PathNode> nodes, PathNode parent) {
        if (!nodes.contains(parent)) {
            nodes.add(parent);
            for (NodePair np : parent.getChildren()) {
                Integer count = segments.get(np.getName());
                if (count == null) {
                    count = new Integer(0);
                }
                segments.put(np.getName(), ++count);
                buildSegments(segments, nodes, np.getConnection());
            }
        }
    }

    /**
     * TODO ???
     */
    private byte[] toByteArray(int value) {
        return new byte[] {
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value};
    }

    private void condenseSubTreeNodes(PathNode location) {
        // "equivalent" parents are merged
        List<PathNode> parentResult = new ArrayList<PathNode>();
        parentResult.addAll(location.getParents());
        for (PathNode parent1 : location.getParents()) {
            if (!parentResult.contains(parent1)) {
                continue;
            }
            for (PathNode parent2 : location.getParents()) {
                if (!parentResult.contains(parent2) ||
                    parent2.getId() == parent1.getId()) {
                    continue;
                }
                if (parent1.isEquivalentTo(parent2)) {
                    // we merge them into smaller Id
                    PathNode merged = parent1.getId() < parent2.getId() ?
                        parent1 : parent2;
                    PathNode toRemove = parent1.getId() < parent2.getId() ?
                        parent2 : parent1;

                    // track down the name of the string in the grandparent
                    //  that points to parent
                    String name = "";
                    PathNode oneParent = toRemove.getParents().get(0);
                    for (NodePair child : oneParent.getChildren()) {
                        if (child.getConnection().getId() == toRemove.getId()) {
                            name = child.getName();
                            break;
                        }
                    }

                    // copy grandparents to merged parent node.
                    List<PathNode> movingParents = toRemove.getParents();
                    merged.addParents(movingParents);

                    // all grandparents with name now point to merged node
                    for (PathNode pn : toRemove.getParents()) { 
                        for (NodePair child : pn.getChildren()) {
                            if (child.getName().equals(name)) {
                                child.setConnection(merged);
                            }
                        }
                    }
                    parentResult.remove(toRemove);
                }
            }
        }
        location.setParents(parentResult);
        for (PathNode pn : location.getParents()) {
            condenseSubTreeNodes(pn);
        }
    }
}

