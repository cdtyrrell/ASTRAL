package phylonet.coalescent;


import java.util.List;

import phylonet.lca.SchieberVishkinLCA;
import phylonet.tree.model.Tree;
import phylonet.tree.model.sti.STITree;
import phylonet.tree.model.sti.STITreeCluster.Vertex;

public class DLInference extends DPInference<STBipartition> {
	private int optimizeDuploss = 1; //one means dup, 3 means duploss
	//Map<STITreeCluster, Vertex> clusterToVertex;
	
	public DLInference(boolean rooted, boolean extrarooted, List<Tree> trees,
			List<Tree> extraTrees, boolean exactSolution, boolean duploss) {
		super(rooted, extrarooted, trees, extraTrees, exactSolution);
		this.optimizeDuploss = duploss ? 3 : 1;
	}

	public int getOptimizeDuploss() {
		return this.optimizeDuploss; 
	}

	public void scoreGeneTree(STITree st) {
		// first calculated duplication cost by looking at gene trees. 
		
		SchieberVishkinLCA lcaLookup = new SchieberVishkinLCA(st);
		Integer duplications = 0;
		Integer losses = 0;
		Integer lossesstd = 0;
		
		for (Tree gtTree : this.trees) {
			int[] res = calc(gtTree,lcaLookup, st);
			duplications += res[0];
			losses += res[1];
			
			STITree hmst = new STITree(st);
			//hmst.constrainByLeaves(Arrays.asList(gtTree.getLeaves()));
			SchieberVishkinLCA hmlcaLookup = new SchieberVishkinLCA(hmst);
			int[] res2 = calc(gtTree,hmlcaLookup, hmst);
			
			lossesstd += res2[2];
		}
		System.out.println("Total number of duplications is: "+duplications);
		System.out.println("Total number of losses (bd) is: "+losses);
		System.out.println("Total number of losses (std) is: "+lossesstd);
		System.out.println("Total number of duploss (bd) is: " + (losses+duplications));
		System.out.println("Total number of duploss (st) is: " + (lossesstd+duplications));
		System.out.println("Total weighted (wd = "+this.getDLbdWeigth()+") loss is: " + (lossesstd + this.getDLbdWeigth()*(losses-lossesstd)));
	}


	int getTotalCost(Vertex all) {
		return (int) (((DLWeightCounter)this.counter).sigmaNs - all._max_score);
	}


	@Override
	ComputeMinCostTask<STBipartition> newComputeMinCostTask(DPInference<STBipartition> dlInference,
			Vertex all, ClusterCollection clusters) {
		return new DLComputeMinCostTask( (DLInference) dlInference, all,  clusters);
	}

	DLClusterCollection newClusterCollection() {
		return new DLClusterCollection(stTaxa.length);
	}
	
	DLWeightCounter newCounter(ClusterCollection clusters) {
		return new DLWeightCounter(gtTaxa, stTaxa, rooted, (DLClusterCollection)clusters);
	}

}
