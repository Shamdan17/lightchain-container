package skipGraph;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import util.Const;
import util.Util;

/**
 * @author Shadi Hamdan
 *
 */

public class LookupTable {
	private int maxLevels;
	private Map<Integer, NodeInfo> dataNodes;
	private Map<Integer, Table> lookup;

	/*
	 * The buffer is there so we can finalize a node's table and insertion before we
	 * add it to the other nodes. This prevents any access to it during search etc.
	 * So it is basically to prevent access to the node's lookup table until it is
	 * fully inserted.
	 */
	private NodeInfo nodeBuffer;
	private Table tableBuffer;

	public LookupTable(int maxLevels) {
		this.maxLevels = maxLevels;
		this.dataNodes = new HashMap<>();
		this.lookup = new HashMap<>();
	}

	public int size() {
		return dataNodes.size();
	}

	public Set<Integer> keySet() {
		return lookup.keySet();
	}

	public int bufferNumID() {
		if (nodeBuffer == null)
			return -1;
		return nodeBuffer.getNumID();
	}

	/**
	 * Adds a node to the data nodes. Warning: Only use this if the node you are
	 * adding is properly initialized and ready to be accessed. If you still want to
	 * initialize the node (eg. the lookup table is not finalized or you do not want
	 * it to be accessible yet) then use
	 * {@link LookupTable#initializeNode(NodeInfo)}
	 * 
	 * @param nd The node that is to be added the lookup
	 * @return false if the numID given was added previously, true otherwise.
	 */
	public synchronized boolean addNode(NodeInfo node) {
		NodeInfo ret = dataNodes.put(node.getNumID(), node);
		if (ret == null) {
			lookup.put(node.getNumID(), new Table());
		}
		return ret == null;
	}

	/**
	 * Adds the node to the buffer. This allows the user to finish finalizing the
	 * node's lookup table before making it accessible. This also makes the node
	 * inaccessible from getBestNum and getBestName. Once the node is finalized, you
	 * can use {@link LookupTable#finalizeNode()} to commit the node to the lookup
	 * table.
	 * 
	 * @param node The NodeInfo of the node you want to add to the buffer.
	 */
	public void initializeNode(NodeInfo node) {
		nodeBuffer = node;
		tableBuffer = new Table();
		tableBuffer.lockTable();
	}

	/**
	 * Commits the node in buffer to the lookup table.
	 * 
	 * @return Returns true if the node was committed properly.
	 * @return Returns false if the node was not initialized properly and thus not
	 *         committed.
	 */
	public boolean finalizeNode() {
		if (this.nodeBuffer == null || this.tableBuffer == null)
			return false;
		else {
			dataNodes.put(nodeBuffer.getNumID(), nodeBuffer);
			lookup.put(nodeBuffer.getNumID(), tableBuffer);
			tableBuffer.unlockTable();
			nodeBuffer = null;
			tableBuffer = null;
			return true;
		}
	}

	/**
	 * Removes all references to the node with the given numID
	 * 
	 * @param numID the numID of the node you want to remove
	 * @return the stored node info of the given numID.
	 */
	public synchronized NodeInfo remove(int numID) {
		lookup.remove(numID);
		return dataNodes.remove(numID);
	}

	/**
	 * Gets the information of the node with the given numID
	 * 
	 * @param numID the numID of the node you want to remove
	 * @return the stored node info of the given numID
	 */
	public synchronized NodeInfo get(int numID) {
		// if the requested node is the one currently in the buffer
		// then it is okay return its NodeInfo
		if (nodeBuffer != null && nodeBuffer.getNumID() == numID) {
			return nodeBuffer;
		}
		return dataNodes.get(numID);
	}

	/**
	 * Get the neighbor of the node with the given numID at the given level and
	 * direction.
	 * 
	 * @param numID     The numID of the node that you want to check the neighbour
	 *                  of
	 * @param level     The level on the lookup table
	 * @param direction The direction (lookupTable.RIGHT or lookupTable.LEFT)
	 * @return The information of the desired neighbour or null if the numID is
	 *         invalid
	 */
	public synchronized NodeInfo get(int numID, int level, int direction) {
		// if the lookup table of the node in the buffer is to be accessed,
		// then this will cause a block at this point until finalizeNode is 
		// called to unlock the tableBuffer.
		if (nodeBuffer != null && nodeBuffer.getNumID() == numID) {
			tableBuffer.get(level, direction);
		}
		if (!dataNodes.containsKey(numID))
			return null;
		return lookup.get(numID).get(level, direction);
	}

	/**
	 * Put the given newNode as a neighbor of the node with the given numID at the
	 * given level and direction if the node in place is the given expectedOldNode
	 * 
	 * @param numID           The numID of the node that you want to check the
	 *                        neighbour of
	 * @param level           The level on the lookup table
	 * @param direction       The direction (lookupTable.RIGHT or lookupTable.LEFT)
	 * @param newNode         The node that you want in this location
	 * @param expectedOldNode The node that you think is in this location (This is
	 *                        to ensure that the lookup has not been modified since
	 *                        you last used "get()"
	 * @return Returns true if the node was placed properly and the expectedOldNode
	 *         was what it replaced. False and the lookup is not modified otherwise.
	 */
	public boolean put(int numID, int level, int direction, NodeInfo newNode, NodeInfo expectedOldNode) {
		if (nodeBuffer != null && nodeBuffer.getNumID() == numID) {
			return tableBuffer.safePut(level, direction, newNode, expectedOldNode);
		}
		if (!lookup.containsKey(numID))
			return false;
		return lookup.get(numID).safePut(level, direction, newNode, expectedOldNode);
	}

	/**
	 * Returns the data node with the numID that is closest to the current node.
	 * 
	 * @param numID The numID you are looking for
	 * @return The numID of the node that is closest to the argument.
	 */
	public int getBestNum(int numID) {
		long bestDif = Long.MAX_VALUE;
		int bestNum = -1;
		for (int cur : dataNodes.keySet()) {
			int dif = Math.abs(numID - cur);
			if (dif < bestDif) {
				bestDif = dif;
				bestNum = cur;
			}
		}
		return bestNum;
	}

	/*
	 * This method receives a nameID and returns the index of the data node which
	 * has the most common prefix with the given nameID
	 */
	public int getBestName(String name, int direction) {
		try {
			int best = -1;
			int num = -1;
			for (int cur : dataNodes.keySet()) {
				if (num == -1)
					num = cur;
				int tmp = Util.commonBits(name, dataNodes.get(cur).getNameID());
				if (tmp > best) {
					best = tmp;
					num = cur;
				}
			}
			for (int cur : dataNodes.keySet()) {
				int bits = Util.commonBits(name, dataNodes.get(cur).getNameID());
				if (bits == best) {
					if (direction == Const.RIGHT) {
						if (dataNodes.get(cur).getNumID() > num) {
							num = dataNodes.get(cur).getNumID();
						}
					} else if (dataNodes.get(cur).getNumID() < num) {
						num = dataNodes.get(cur).getNumID();
					}
				}
			}
			return num;
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

	/*
	 * Print the contents of the lookup table
	 */
	public void printLookup(int num) {
		System.out.println("\n");
		for (int i = maxLevels - 1; i >= 0; i--) {
			NodeInfo lNode = get(num, i, Const.LEFT);
			NodeInfo rNode = get(num, i, Const.RIGHT);
			if (lNode == null)
				Util.logLine("null\t");
			else {
				Util.logLine(lNode.toString() + "\t");
			}
			if (rNode == null)
				Util.logLine("null\t");
			else {
				Util.logLine(rNode.toString() + "\t");
			}
			Util.log("\n\n");
		}
	}

	public int getMaxLevels() {
		return maxLevels;
	}

	class Table {
		ReadWriteLock lock;
		private ConcurrentHashMap<Integer, NodeInfo> table;

		public Table() {
			table = new ConcurrentHashMap<Integer, NodeInfo>();
			lock = new ReentrantReadWriteLock(true);
		}

		public void lockTable() {
			lock.writeLock().lock();
		}

		public void unlockTable() {
			lock.writeLock().unlock();
		}

		public NodeInfo get(int level, int direction) {

			if (!validate(level, direction))
				return null;

			lock.readLock().lock();
			try {
				return table.get(getInd(level, direction));
			} finally {
				lock.readLock().unlock();
			}
		}

		private NodeInfo put(int level, int direction, NodeInfo newNode) {
			if (!validate(level, direction))
				return null;

			if (newNode == null)
				return remove(level, direction);

			NodeInfo res = table.put(getInd(level, direction), newNode);

			return res;
		}

		private NodeInfo remove(int level, int direction) {

			return table.remove(getInd(level, direction));

		}

		// TODO: see if we can get rid of expectedOldNode==null
		public boolean safePut(int level, int direction, NodeInfo newNode, NodeInfo expectedOldNode) {
			NodeInfo cur = put(level, direction, newNode);
			if (expectedOldNode == null || equal(cur, expectedOldNode))
				return true;
			else {
				put(level, direction, cur);
				return false;
			}
		}

		private boolean equal(NodeInfo nodeA, NodeInfo nodeB) {
			if (nodeA == null && nodeB == null) {
				return true;
			} else if (nodeA == null || nodeB == null)
				return false;

			return nodeA.equals(nodeB);
		}

		private int getInd(int level, int direction) {
			return 2 * level + direction;
		}

		private boolean validate(int level, int direction) {
			return validateLevel(level) && validateDir(direction);
		}

		private boolean validateLevel(int level) {
			return level >= 0 && level <= maxLevels;
		}

		private boolean validateDir(int direction) {
			return direction == 1 || direction == 0;
		}
	}
}