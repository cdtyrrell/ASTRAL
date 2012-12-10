package phylonet.coalescent;

import java.util.ArrayList;
import java.util.Collection;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RecursiveTask;

import phylonet.coalescent.DuplicationWeightCounter.CalculateWeightTask;
import phylonet.coalescent.MGDInference_DP.TaxonNameMap;
import phylonet.tree.model.Tree;
import phylonet.tree.model.sti.STITreeCluster;
import phylonet.tree.model.sti.STITreeCluster.Vertex;

public class ComputeMinCostTask {

	/**
	 * 
	 */
	private static final long serialVersionUID = 244989909835073096L;
	private MGDInference_DP inference;
	private Vertex v;
	private ClusterCollection clusters;

	protected Integer compute() {
		try {
			return computeMinCost();
		} catch (CannotResolveException e) {
			return null;
		}
	}

	public ComputeMinCostTask(MGDInference_DP inference, Vertex v,ClusterCollection clusters) {
		this.inference = inference;
		this.v = v;
		this.clusters = clusters;
	}
	
	//final int maxEL = 10000000;
	ClusterCollection containedVertecies;
	
	private void add_complementary_clusters(int clusterSize) {
		Iterator<Set<Vertex>> it = containedVertecies.getSubClusters().iterator();
		while (it.hasNext()) {
			Collection<Vertex> subClusters = new ArrayList<Vertex>(it.next());
			int i = -1;
			for (Vertex x : subClusters) {
				i = i > 0 ? i: x.getCluster().getClusterSize();
				int complementarySize  = clusterSize - i;													
					containedVertecies.addCluster(getCompleteryVertx(x, v.getCluster()),complementarySize);
			}
			if (i < clusterSize * inference.CD) {
				return;
			}

		}
	}
	private int computeMinCost() throws CannotResolveException {

		boolean rooted = inference.rooted;
		List<Tree> trees = inference.trees;
		DuplicationWeightCounter counter = inference.counter;
		TaxonNameMap taxonNameMap = inference.taxonNameMap;
		
		// -2 is used to indicate it cannot be resolved
		if (v._done == 2) {
			throw new CannotResolveException(v.getCluster().toString());
		}
		// Already calculated. Don't re-calculate.
		if (v._done == 1) {
			return v._max_score;
		}
		//

		int clusterSize = v.getCluster().getClusterSize();

		// SIA: base case for singelton clusters.
		if (clusterSize <= 1) {
			int _el_num = -1;
			if (inference.optimizeDuploss == 3) {
				if (taxonNameMap == null) {
					_el_num = DeepCoalescencesCounter.getClusterCoalNum(
							trees, v.getCluster(), rooted);
				} else {
					_el_num = DeepCoalescencesCounter.getClusterCoalNum(
							trees, v.getCluster(), taxonNameMap, rooted);
				}
			} else {
				_el_num = 0;
			}

			//v._min_cost = 0;
			v._max_score = - _el_num;
			v._min_lc = (v._min_rc = null);
			v._done = 1;
			return v._max_score;
		}

		// STBipartition bestSTB = null;
		if (inference.fast) {
			/*Set<STBipartition> clusterBiPartitions = counter
					.getClusterBiPartitions(v.getCluster());
			fast_STB_based_inference(trees, counter,
					clusterBiPartitions);*/
			throw new RuntimeException("Fast version Not implemented");
			
		} else {
			List<Integer> El = new ArrayList<Integer>();
			for (int k = 0; k < trees.size(); k++) El.add(null);
			
			boolean tryAnotherTime = false;

			containedVertecies = clusters.getContainedClusters(v.getCluster());
				
			do {
				tryAnotherTime = false;
				
				if (clusterSize >= inference.counter.stTaxa.length * inference.CS) {
					add_complementary_clusters(clusterSize);
				}
	
				for (STBipartition bi : containedVertecies.getClusterResolutions()) {
					try {
							Vertex smallV = containedVertecies.getVertexForCluster(bi.cluster1);
							Vertex bigv = containedVertecies.getVertexForCluster(bi.cluster2);
							ComputeMinCostTask smallWork = new ComputeMinCostTask(
									inference, smallV,containedVertecies);
							ComputeMinCostTask bigWork = new ComputeMinCostTask(
									inference, bigv,containedVertecies);
							CalculateWeightTask weigthWork = null;

							// MP_VERSION: smallWork.fork();
							Integer rscore = bigWork.compute();
							
							if (rscore == null) {
								// MP_VERSION: weigthWork.cancel(false);
								// MP_VERSION: smallWork.cancel(false);
								throw new CannotResolveException(
										bigv.getCluster().toString());
							}

							Integer lscore;
							// MP_VERSION: lscore = smallWork.join();
							lscore = smallWork.compute();

							if (lscore == null) {
								// MP_VERSION: 	weigthWork.cancel(false);
								throw new CannotResolveException(
										smallV.getCluster().toString());
							}
							// MP_VERSION: w = weigthWork.join();

							Integer w = counter
									.getCalculatedBiPartitionDPWeight(bi);
							if (w == null) {
								weigthWork = counter.new CalculateWeightTask(
										bi,containedVertecies);
								// MP_VERSION: smallWork.fork();
								w = weigthWork.compute();									
							}

							Integer e = inference.optimizeDuploss == 3 ?
									calculateDLCost(El, smallV, bigv) : 0;

							int c = inference.optimizeDuploss * w - e;

							if ((v._max_score != -1)
									&& (lscore + rscore + c < v._max_score)) {
								continue;
							}
							v._max_score = (lscore + rscore + c);
							v._min_lc = smallV;
							v._min_rc = bigv;
							v._c = c;

						} catch (CannotResolveException c) {
							// System.err.println("Warn: cannot resolve: " +
							// c.getMessage());
						}
					}
				if (v._min_lc == null || v._min_rc == null) {
					if (clusterSize <= 5) {
						addAllPossibleSubClusters(v.getCluster(),
							containedVertecies);
						tryAnotherTime = true;
					} else if (clusterSize > 1) {	
						//System.err.println(maxSubClusters);
						Iterator<Set<Vertex>> it = containedVertecies.getSubClusters().iterator();
						if (it.hasNext()) {
							Collection<Vertex> biggestSubClusters = new ArrayList<Vertex>(it.next());
							int i = -1;
							for (Vertex x : biggestSubClusters) {
								i = i > 0 ? i: x.getCluster().getClusterSize();
								int complementarySize  = clusterSize - i;						
								if ( complementarySize > 1) {
									tryAnotherTime |= containedVertecies.addCluster(getCompleteryVertx(x, v.getCluster()),complementarySize);
								}
							}
							/*	if (tryAnotherTime && clusterSize > 10) {
								System.err
									.println("Adding up to " + biggestSubClusters.size()+" extra |"+i+"| clusters (complementary of included clusters) for size "
											+ clusterSize + " : " + v.getCluster()+"\n" +
													containedVertecies.getClusterCount());
							}*/
						}
						
					}
				}
			} while (tryAnotherTime); 
		}

		if (v._min_lc == null || v._min_rc == null) {
			if (MGDInference_DP._print) {
				System.err.println("WARN: No Resolution found for ( "
						+ v.getCluster().getClusterSize() + " taxa ):\n"
						+ v.getCluster());
			}
			v._done = 2;
			throw new CannotResolveException(v.getCluster().toString());
		}
/*		if (clusterSize > 450){
			System.out.println(v+" \nis scored "+(v._max_score ) + " by \n"+v._min_lc + " \n"+v._min_rc);
		}
*/		/*
		 * if (clusterSize > 5){ counter.addGoodSTB(bestSTB, clusterSize); }
		 */
		v._done = 1;
		return v._max_score ;
	}

	private Integer calculateDLCost(List<Integer> El, Vertex smallV, Vertex bigv) {
		Integer e = 0;
		List<Tree> trees = inference.trees;
		TaxonNameMap taxonNameMap = inference.taxonNameMap;
		if (inference.HomomorphicDL) {
			for (int k = 0; k < trees.size(); k++) {
				Tree tr = trees.get(k);
				STITreeCluster treeAll = inference.counter.treeAlls.get(k);
				if (smallV.getCluster().isDisjoint(treeAll)
						|| bigv.getCluster().isDisjoint(treeAll)) {
					continue;
				}
				if (El.get(k) == null) {
					if (taxonNameMap == null) {
						El.set(k, DeepCoalescencesCounter
								.getClusterCoalNum_rooted(tr, v.getCluster()));
					} else {
						El.set(k, DeepCoalescencesCounter
								.getClusterCoalNum_rooted(tr, v.getCluster(),
										taxonNameMap));
					}
				}
				e += El.get(k);
				// System.err.println("E for " + v.getCluster() + " is "+e +
				// " and k is  " + k);
			}
		} else {
			for (int k = 0; k < trees.size(); k++) {
				Tree tr = trees.get(k);
				STITreeCluster treeAll = inference.counter.treeAlls.get(k);
				int extraTerms = 0;
				boolean pDisJoint = smallV.getCluster().isDisjoint(treeAll);
				boolean qDisJoint = bigv.getCluster().isDisjoint(treeAll);
				if (pDisJoint && qDisJoint) {
					extraTerms = 0;
				} else if (!pDisJoint && !pDisJoint) {
					extraTerms = 2;
				} else {
					boolean complete = pDisJoint ? 
							bigv.getCluster().getClusterSize() == treeAll.getClusterSize() :
							smallV.getCluster().getClusterSize() == treeAll.getClusterSize(); 
					extraTerms = complete ? 2 : 1;
				}
				if (El.get(k) == null) {
					if (taxonNameMap == null) {
						El.set(k, DeepCoalescencesCounter
								.getClusterCoalNum_rooted(tr, v.getCluster()));
					} else {
						El.set(k, DeepCoalescencesCounter
								.getClusterCoalNum_rooted(tr, v.getCluster(),
										taxonNameMap));
					}
				}
				e += (El.get(k) + extraTerms);
				// System.err.println("E for " + v.getCluster() + " is "+e +
				// " and k is  " + k);
			}
		}
		return e;
	}

	void addAllPossibleSubClusters(STITreeCluster cluster, ClusterCollection containedVertecies) {
		int size = cluster.getClusterSize();
		for (int i = cluster.getBitSet().nextSetBit(0); i>=0 ;i = cluster.getBitSet().nextSetBit(i+1)){
			STITreeCluster c = new STITreeCluster(cluster);
			c.getBitSet().clear(i);

			Vertex nv = c.new Vertex();
			containedVertecies.addCluster(nv, size -1);

			addAllPossibleSubClusters(c, containedVertecies);
		}
	}
	
	public Vertex getCompleteryVertx(Vertex x, STITreeCluster refCluster) {
		STITreeCluster c = x.getCluster();	
		
		STITreeCluster revcluster = new STITreeCluster(refCluster);
		revcluster.getBitSet().xor(c.getBitSet());
		Vertex reverse = revcluster.new Vertex();
		//int size = reverse._cluster.getClusterSize(); 
		return reverse;
	}

	
	/*	public boolean addAllPossibleSubClusters(STITreeCluster cluster) {
	int size = cluster.getClusterSize();
	boolean ret = false;
	for (int i = cluster.getCluster().nextSetBit(0); i>=0 ;i = cluster.getCluster().nextSetBit(i+1)){
		STITreeCluster c = new STITreeCluster(cluster);
		c.getCluster().clear(i);
		ret |= addToClusters(c, size-1);
		ret |= addAllPossibleSubClusters(c);
	}
	return ret;
	}*/
	
	
	/*	public boolean addAllPossibleSubClusters(STITreeCluster cluster) {
		int size = cluster.getClusterSize();
		boolean ret = false;
		for (int i = cluster.getCluster().nextSetBit(0); i>=0 ;i = cluster.getCluster().nextSetBit(i+1)){
			STITreeCluster c = new STITreeCluster(cluster);
			c.getCluster().clear(i);
			ret |= addToClusters(c, size-1);
			ret |= addAllPossibleSubClusters(c);
		}
		return ret;
	}*/

}
