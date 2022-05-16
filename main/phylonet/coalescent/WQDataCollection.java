package phylonet.coalescent;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import phylonet.lca.SchieberVishkinLCA;
import phylonet.tree.io.ParseException;
import phylonet.tree.model.MutableTree;
import phylonet.tree.model.TMutableNode;
import phylonet.tree.model.TNode;
import phylonet.tree.model.Tree;
import phylonet.tree.model.sti.STINode;
import phylonet.tree.model.sti.STITree;
import phylonet.tree.model.sti.STITreeCluster;
import phylonet.tree.util.Trees;
import phylonet.util.BitSet;

/**
 * Sets up the set X
 * 
 * @author smirarab
 * 
 */
public class WQDataCollection extends AbstractDataCollection<Tripartition> implements Cloneable {

	static TaxonIdentifier taxid = GlobalMaps.taxonIdentifier;


	/**
	 * Similarity matrices for individuals. Used for setting up set X
	 */
	protected Matrix geneMatrix;
	/**
	 * Similarity matrices for species. Used for setting up set X
	 */
	protected Matrix speciesMatrix;

	// Parameters of ASTRAL-II heuristics
	protected boolean SLOW = false;
	protected final double[] GREEDY_ADDITION_THRESHOLDS = new double[] { 0,
			1 / 100., 1 / 50., 1 / 20., 1 / 10., 1 / 5., 1 / 3. };
	protected final int GREEDY_DIST_ADDITTION_LAST_THRESHOLD_INDX = 3;
	protected final int GREEDY_ADDITION_MAX_POLYTOMY_MIN = 50;
	protected final int GREEDY_ADDITION_MAX_POLYTOMY_MULT = 25;
	protected final int GREEDY_ADDITION_DEFAULT_RUNS = 10;
	protected final int GREEDY_ADDITION_MIN_FREQ = 5;
	protected final double GREEDY_ADDITION_MIN_RATIO = 0.01;
	protected final int GREEDY_ADDITION_MAX = 100;
	protected final int GREEDY_ADDITION_IMPROVEMENT_REWARD = 2;
	protected final int POLYTOMY_RESOLUTIONS = 3;
	protected final double POLYTOMY_RESOLUTIONS_GREEDY_GENESAMPLE = 0.9;
	protected List<Tree> geneTrees;
	protected boolean outputCompleted;
	protected String outfileName; 
	protected final int POLYTOMY_RESOLUTIONS_SAMPLE_GRADIENT = 15000;
	protected final int POLYTOMY_SIZE_LIMIT_MAX = 100000;
	protected int polytomySizeLimit = POLYTOMY_SIZE_LIMIT_MAX;

	// Just a reference to gene trees from inference (for convinience).
	protected List<Tree> originalInompleteGeneTrees;
	/**
	 * Gene trees after completion.
	 */
	protected List<Tree> compatibleCompleteGeneTrees;

	// A reference to user-spcified global options.
	protected Options options;

	public WQDataCollection(IClusterCollection clusters,
			AbstractInference inference) {
		this.clusters = clusters;
		this.SLOW = inference.options.getAddExtra() == 2; //TODO: correct?
		this.originalInompleteGeneTrees = inference.trees;
		this.compatibleCompleteGeneTrees = new ArrayList<Tree>();
		this.options = inference.options;
	}

	/**
	 * Once we have chosen a subsample with only one individual per species, we
	 * can use this metod to compute and add bipartitions from the input gene
	 * trees to set X. This is equivalent of ASTRAL-I set X computed for the
	 * subsample.
	 * 
	 * @param allGenesGreedy
	 * @param trees
	 * @param taxonSample
	 * @param greedy
	 *            is the greedy consensus of all gene trees
	 */
	protected void addBipartitionsFromSignleIndTreesToX(Tree tr,
			Collection<Tree> baseTrees, TaxonIdentifier id,
			boolean resolvePolytomies,
			boolean saveClusters) {	
		Stack<STITreeCluster> stack = new Stack<STITreeCluster>();

		for (TNode node : tr.postTraverse()) {
			if (node.isLeaf()) {
				STITreeCluster cluster = GlobalMaps.taxonNameMap
						.getSpeciesIdMapper().getSTTaxonIdentifier()
						.getClusterForNodeName(node.getName());
				stack.add(cluster);
				addSpeciesBipartitionToX(cluster);
				if (saveClusters) {
					((STINode)node).setData(cluster);
				}

			} else {
				ArrayList<BitSet> childbslist = new ArrayList<BitSet>();

				BitSet bs = new BitSet(GlobalMaps.taxonNameMap
						.getSpeciesIdMapper().getSTTaxonIdentifier()
						.taxonCount());
				for (TNode child : node.getChildren()) {
					STITreeCluster pop = stack.pop();
					childbslist.add(pop.getBitSet());
					bs.or(pop.getBitSet());
				}

				/**
				 * Note that clusters added to the stack are currently using the
				 * global taxon identifier that has all individuals
				 */
				STITreeCluster cluster = Factory.instance.newCluster(GlobalMaps.taxonNameMap
						.getSpeciesIdMapper().getSTTaxonIdentifier());
				cluster.setCluster(bs);
				stack.add(cluster);
				if (saveClusters) {
					((STINode)node).setData(cluster);
				}

				//boolean bug = false;
				try {
					if (addSpeciesBipartitionToX(cluster)) {

					}
				} catch (Exception e) {
					//bug = true;
					//					Logging.log("node : "+node.toString());
					//					Logging.log("cluster : "+cluster);
					//					Logging.log(childbslist.size());
					//					Logging.log(childbslist);
					//					Logging.log("bs : "+bs);
					e.printStackTrace(); 
				}



				/**
				 * For polytomies, if we don't do anything extra, the cluster
				 * associated with the polytomy may not have any resolutions in
				 * X. We don't want that. We use the greedy consensus trees and
				 * random sampling to add extra bipartitions to the input set
				 * when we have polytomies.
				 */

				if (resolvePolytomies && childbslist.size() > 2) {
					BitSet remaining = (BitSet) bs.clone();
					remaining.flip(0, GlobalMaps.taxonNameMap
							.getSpeciesIdMapper().getSTTaxonIdentifier()
							.taxonCount());				
					boolean isRoot = remaining.isEmpty();
					int d = childbslist.size() + (isRoot ? 0 : 1);
					BitSet[] polytomy = new BitSet[d];
					int i = 0;
					for (BitSet child : childbslist) {
						polytomy[i++] = child;
					}
					if (!isRoot) {
						polytomy[i] = remaining;
					}

					// TODO: do multiple samples
					int gradient = Integer.MAX_VALUE;
					for(int ii = 0 ; ii < 3; ii++){
						int b = this.clusters.getClusterCount();					
						HashMap<String, Integer> randomSample = this.
								randomSampleAroundPolytomy(polytomy, GlobalMaps.taxonNameMap
										.getSpeciesIdMapper().getSTTaxonIdentifier());

						for (Tree gct : baseTrees){

							for (BitSet restrictedBitSet : Utils.getBitsets(
									randomSample, gct)) {
								/**
								 * Before adding bipartitions from the greedy consensus
								 * to the set X we need to add the species we didn't
								 * sample to the bitset.
								 */
								restrictedBitSet = this.addbackAfterSampling(polytomy,
										restrictedBitSet, GlobalMaps.taxonNameMap
										.getSpeciesIdMapper()
										.getSTTaxonIdentifier());
								this.addSpeciesBitSetToX(restrictedBitSet);
							}
							gradient = this.clusters.getClusterCount() - b;
						}
						gradient = this.clusters.getClusterCount() - b;
					}

				}

			}
		}
	}


	/**
	 * How many rounds of sampling should we do? Completely arbitrarily at this
	 * point. Should be better explored.
	 * 
	 * @param userProvidedRounds
	 * @return
	 */
	protected int getSamplingRepeationFactor(int userProvidedRounds) {
		if (userProvidedRounds < 1) {
			double sampling = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
					.meanSampling();
			int repeat = (int) (int) Math.ceil(Math.log(2 * sampling)
					/Math.log(2));
			return repeat;
		} else {
			return userProvidedRounds;
		}

	}

	/**
	 * Completes an incomplete tree for the purpose of adding to set X
	 * Otherwise, bipartitions are meaningless.
	 * 
	 * @param tr
	 * @param gtAllBS
	 * @return
	 */
	Tree getCompleteTree(Tree tr, BitSet gtAllBS) {

		if (gtAllBS.cardinality() < 3) {
			throw new RuntimeException("Tree " + tr.toNewick()
			+ " has less than 3 taxa; it cannot be completed");
		}
		STITree trc = new STITree(tr);

		TreeSet<String> leaves = new TreeSet<String>(Arrays.asList(tr.getLeaves()));
		
		Trees.removeBinaryNodes(trc);

		for (int missingId = gtAllBS.nextClearBit(0); missingId < taxid
				.taxonCount(); missingId = gtAllBS.nextClearBit(missingId + 1)) {

			if (leaves.contains(taxid.getTaxonName(missingId))){
				continue; // This has been added already by someone else. 
			}

			int closestId = geneMatrix.getClosestPresentTaxonId(gtAllBS,
					missingId);

			STINode closestNode = trc.getNode(taxid
					.getTaxonName(closestId));

			trc.rerootTreeAtNode(closestNode);
			Trees.removeBinaryNodes(trc);

			Iterator cit = trc.getRoot().getChildren().iterator();
			STINode c1 = (STINode) cit.next();
			STINode c2 = (STINode) cit.next();
			STINode start = closestNode == c1 ? c2 : c1;

			int c1random = -1;
			int c2random = -1;
			while (true) {
				if (start.isLeaf()) {
					break;
				}

				cit = start.getChildren().iterator();
				c1 = (STINode) cit.next();
				c2 = (STINode) cit.next();

				// TODO: what if c1 or c2 never appears in the same tree as
				// missing and closestId .
				if (c1random == -1) {
					c1random = taxid.taxonId(Utils
							.getLeftmostLeaf(c1));
				}
				if (c2random == -1) {
					c2random = taxid.taxonId(Utils
							.getLeftmostLeaf(c2));
				}
				int betterSide = geneMatrix.getBetterSideByFourPoint(
						missingId, closestId, c1random, c2random);
				if (betterSide == closestId) {
					break;
				} else if (betterSide == c1random) {
					start = c1;
					// Currently, c1random is always under left side of c1
					c2random = -1;
				} else if (betterSide == c2random) {
					start = c2;
					// Currently, c2random is always under left side of c2
					c1random = c2random;
					c2random = -1;
				}

			}
			if (start.isLeaf()) {
				STINode newnode = start.getParent().createChild(
						taxid.getTaxonName(missingId));
				STINode newinternalnode = start.getParent().createChild();
				newinternalnode.adoptChild(start);
				newinternalnode.adoptChild(newnode);
			} else {
				STINode newnode = start.createChild(taxid
						.getTaxonName(missingId));
				STINode newinternalnode = start.createChild();
				newinternalnode.adoptChild(c1);
				newinternalnode.adoptChild(c2);
			}
		}

		return trc;
	}

	/**
	 * Given a bitset that shows one side of a bipartition this method adds the
	 * bipartition to the set X. Importantly, when the input bitset has only one
	 * (or a sbuset) of individuals belonging to a species set, the other
	 * individuals from that species are also set to one before adding the
	 * bipartition to the set X. Thus, all individuals from the same species
	 * will be on the same side of the bipartition. These additions are done on
	 * a copy of the input bitset not the instance passed in.
	 * 
	 * @param stBitSet
	 * @return was the cluster new?
	 */
	// private boolean addSingleIndividualBitSetToX(final BitSet bs) {
	// STITreeCluster cluster = taxid.newCluster();
	// cluster.setCluster(bs);
	// return this.addSingleIndividualBipartitionToX(cluster);
	// }
	private boolean addSpeciesBitSetToX(final BitSet stBitSet) {
		STITreeCluster cluster = Factory.instance.newCluster(GlobalMaps.taxonNameMap.getSpeciesIdMapper()
				.getSTTaxonIdentifier());
		//		BitSet sBS = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
		//				.getGeneBisetForSTBitset(bs);
		//		cluster.setCluster(sBS);
		cluster.setCluster(stBitSet);
		return this.addSpeciesBipartitionToX(cluster);
	}

	/**
	 * Adds bipartitions to X. When only one individual from each species is
	 * sampled, this method adds other individuals from that species to the
	 * cluster as well, but note that these additions are done on a copy of c1
	 * not c1 itself.
	 */
	private boolean addSpeciesBipartitionToX(final STITreeCluster stCluster) {
		boolean added = false;		

		STITreeCluster c1GT = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
				.getGeneClusterForSTCluster(stCluster);

		added |= this.addCompletedSpeciesFixedBipartionToX(c1GT,
				c1GT.complementaryCluster());

		// if (added) { System.err.print(".");}

		return added;
	}

	/**
	 * Adds extra bipartitions added by user using the option -e and -f
	 */
	public void addExtraBipartitionsByInput(List<Tree> extraTrees,
			boolean extraTreeRooted) {

		//List<Tree> completedExtraGeeneTrees = new ArrayList<Tree>();
		ArrayList<Boolean> res = new ArrayList();
		for (Tree tr : extraTrees) {
			res.add(new addExtraBipartitionByInputLoop(tr).call());
		}

	}

	public class addExtraBipartitionByInputLoop implements Callable<Boolean> {

		public addExtraBipartitionByInputLoop(Tree tr) {
			super();
			this.tr = tr;
		}

		Tree tr;

		@Override
		public Boolean call() {
			String[] gtLeaves = tr.getLeaves();
			STITreeCluster gtAll = Factory.instance.newCluster(taxid);
			for (int i = 0; i < gtLeaves.length; i++) {
				gtAll.addLeaf(taxid.taxonId(gtLeaves[i]));
			}
			Tree trc = getCompleteTree(tr, gtAll.getBitSet());

			STITree stTrc = new STITree(trc);
			GlobalMaps.taxonNameMap.getSpeciesIdMapper().gtToSt((MutableTree) stTrc);
			if(hasPolytomy(stTrc)){
				throw new RuntimeException(
						"Extra tree shouldn't have polytomy ");
			}
			ArrayList<Tree> st = new ArrayList<Tree>();
			st.add(stTrc);
			addBipartitionsFromSignleIndTreesToX(stTrc,st, 
					GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier(), true, false);
			return Boolean.TRUE;
		}


	}


	public void removeTreeBipartitionsFromSetX(STITree st){
		List<STITreeCluster> stClusters = Utils.getGeneClusters(st, taxid);	
		int size;

		for(int i = 0; i < stClusters.size(); i++){
			STITreeCluster cluster = stClusters.get(i);
			size =  cluster.getClusterSize();
			removeCluster(cluster, size);
			//			Logging.log(size+ cluster.toString());
			STITreeCluster comp = cluster.complementaryCluster();			
			if(comp.getClusterSize() < taxid.taxonCount() - 1){
				removeCluster(comp, size);
			}

		}

	}

	public void removeExtraBipartitionsByInput(List<Tree> extraTrees,
			boolean extraTreeRooted) {

		for (Tree tr : extraTrees) {
			Logging.log(tr.toNewick());	
			STITree stTrc = new STITree(tr);
			GlobalMaps.taxonNameMap.getSpeciesIdMapper().gtToSt((MutableTree) stTrc);
			removeTreeBipartitionsFromSetX(stTrc);

		}


	}

	public boolean hasPolytomy(Tree tr){
		for (TNode node : tr.postTraverse()) {
			if(node.getChildCount() > 2){
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds a bipartition to the set X. This method assumes inputs are already
	 * fixed to have all individuals of the same species.
	 * 
	 * @param c1
	 * @param c2
	 * @return was the cluster new?
	 */
	private boolean addCompletedSpeciesFixedBipartionToX(STITreeCluster c1,
			STITreeCluster c2) {
		boolean added = false;
		int size = c1.getClusterSize();
		/*
		 * TODO: check if this change is correct
		 */
		if (size == taxid.taxonCount()
				|| size == 0) {
			return false;
		}
		added |= addToClusters(c1, size);
		size = c2.getClusterSize();
		added |= addToClusters(c2, size);
		return added;
	}

	// /***
	// * Computes and adds partitions from the input set (ASTRAL-I)
	// * Also, adds extra bipartitions using ASTRAL-II heuristics.
	// * Takes care of multi-individual dataset subsampling.
	// */
	// @Override
	// public void formSetX(AbstractInference<Tripartition> inf) {
	//
	// WQInference inference = (WQInference) inf;
	// int haveMissing = preProcess(inference);
	// SpeciesMapper spm = GlobalMaps.taxonNameMap.getSpeciesIdMapper();
	//
	// calculateDistances();
	//
	// if (haveMissing > 0 ) {
	// completeGeneTrees();
	// } else {
	// this.completedGeeneTrees = this.originalInompleteGeneTrees;
	// }
	//
	// /*
	// * Calculate gene tree clusters and bipartitions for X
	// */
	// STITreeCluster all = taxid.newCluster();
	// all.getBitSet().set(0, taxid.taxonCount());
	// addToClusters(all, taxid.taxonCount());
	//
	// Logging.log("Building set of clusters (X) from gene trees ");
	//
	//
	// /**
	// * This is where we randomly sample one individual per species
	// * before performing the next steps in construction of the set X.
	// */
	// int maxRepeat =
	// getSamplingRepeationFactor(inference.options.getSamplingrounds());
	//
	// if (maxRepeat > 1)
	// Logging.log("Average  sampling is "+ spm.meanSampling() +
	// ".\nWill do "+maxRepeat+" rounds of sampling ");
	//
	// //Logging.log(this.completedGeeneTrees.get(0));
	// int prev = 0, firstgradiant = -1, gradiant = 0;
	// for (int r = 0; r < maxRepeat; r++) {
	//
	// Logging.log("------------\n"
	// + "Round " +r +" of individual  sampling ...");
	// SingleIndividualSample taxonSample = new
	// SingleIndividualSample(spm,this.geneMatrix);
	//
	// Logging.log("taxon sample " +
	// Arrays.toString(taxonSample.getTaxonIdentifier().getAllTaxonNames()));
	//
	// List<Tree> contractedTrees =
	// taxonSample.contractTrees(this.completedGeeneTrees);
	//
	// //Logging.log(trees.get(0));
	//
	// addBipartitionsFromSignleIndTreesToX(contractedTrees, taxonSample);
	//
	// Logging.log("Number of clusters after simple addition from gene trees: "
	// + clusters.getClusterCount());
	//
	// if (inference.getAddExtra() != 0) {
	// Logging.log("calculating extra bipartitions to be added at level "
	// + inference.getAddExtra() +" ...");
	// this.addExtraBipartitionByHeuristics(contractedTrees, taxonSample);
	//
	// Logging.log("Number of Clusters after addition by greedy: " +
	// clusters.getClusterCount());
	// gradiant = clusters.getClusterCount() - prev;
	// prev = clusters.getClusterCount();
	// if (firstgradiant == -1)
	// firstgradiant = gradiant;
	// else {
	// //Logging.log("First gradiant: " + firstgradiant+
	// " current gradiant: " + gradiant);
	// if (gradiant < firstgradiant / 10) {
	// //break;
	// }
	// }
	//
	// }
	// }
	// Logging.log();
	//
	// Logging.log("Number of Default Clusters: " +
	// clusters.getClusterCount());
	//
	// }

	/***
	 * Computes and adds partitions from the input set (ASTRAL-I) Also, adds
	 * extra bipartitions using ASTRAL-II heuristics. Takes care of
	 * multi-individual dataset subsampling.
	 */
	@Override
	public void formSetX(AbstractInference<Tripartition> inf) {


		WQInference inference = (WQInference) inf;
		int haveMissing = preProcess(inference);
		SpeciesMapper spm = GlobalMaps.taxonNameMap.getSpeciesIdMapper();

		calculateDistances();

		if (this.options.getAddExtra() != 3) {
			//if (haveMissing > 0) {
			
			// Make gene trees compatible and complete
			completeGeneTrees(haveMissing != 0);
			
			//} else {
			//	this.compatibleCompleteGeneTrees = new ArrayList<Tree>(this.originalInompleteGeneTrees.size()); 
			//	for (Tree t: this.originalInompleteGeneTrees) {
			//		this.compatibleCompleteGeneTrees.add(new STITree(t));
			//	}
			//}
		} else {
			Logging.log("Using extranl trees as completed input gene trees");
			this.compatibleCompleteGeneTrees = new ArrayList<Tree>(
					this.originalInompleteGeneTrees.size());
			if (inference.extraTrees.size() != this.originalInompleteGeneTrees.size())
				Logging.log("WARNING: you provided fewer trees with -p3 -e than there are gene trees. "
						+ "This is not expected");
			for (Tree tr : inference.extraTrees) {

				STITree stTrc = (STITree) tr; //new STITree(tr);
				//GlobalMaps.taxonNameMap.getSpeciesIdMapper().gtToSt((MutableTree) stTrc);
				if(hasPolytomy(stTrc)){
					throw new RuntimeException("Extra tree shouldn't have polytomy ");
				}
				if (stTrc.getLeafCount() != taxid.taxonCount()) {
					throw new RuntimeException("With -p 3, all extra trees should be complete. "
							+ "The following tree has missing data:\n" + tr);
				}
				this.compatibleCompleteGeneTrees.add(stTrc);
			}
		}

		Logging.log("Building set of clusters (X) from gene trees ");

		Logging.logTimeMessage(" WQDataCollection 558-561: ");

		/**
		 * This is where we randomly sample one individual per species before
		 * performing the next steps in construction of the set X.
		 */
		//int firstRoundSampling = 400;

		int secondRoundSampling = getSamplingRepeationFactor(inference.options.getSamplingrounds());;


		ArrayList<SingleIndividualSample> firstRoundSamples = new ArrayList<SingleIndividualSample>();
		int K =100;
		STITreeCluster all = Factory.instance.newCluster(taxid);
		all.getBitSet().set(0, taxid.taxonCount());
		addToClusters(all, taxid.taxonCount());


		int arraySize = this.compatibleCompleteGeneTrees.size();
		List<Tree> [] allGreedies = new List [arraySize];	

		if (GlobalMaps.taxonNameMap.getSpeciesIdMapper().isSingleIndividual()) {
			int gtindex = 0;
			for (Tree gt : this.compatibleCompleteGeneTrees) {
				ArrayList<Tree> tmp = new ArrayList<Tree>();
				STITree gtrelabelled = new STITree( gt);
				GlobalMaps.taxonNameMap.getSpeciesIdMapper().gtToSt(
						(MutableTree) gtrelabelled);
				tmp.add(gtrelabelled);
				allGreedies[gtindex++] = tmp;
			}
		} else {
			/*
			 * instantiate k random samples
			 */

			for (int r = 0; r < secondRoundSampling*K; r++) {

				//Logging.log("------------\n" + "sample " + (r+1)
				//	+ " of individual  sampling ...");
				SingleIndividualSample taxonSample = new SingleIndividualSample(
						spm, this.geneMatrix);
				firstRoundSamples.add(taxonSample);

			}

			Logging.log("In second round sampling "
					+secondRoundSampling+" rounds will be done");
			Logging.logTimeMessage("TIME TOOK FROM LAST NOTICE WQDataCollection 621-624");

			int gtindex = 0;
			for (Tree gt : this.compatibleCompleteGeneTrees) {
				ArrayList<Tree> firstRoundSampleTrees = new ArrayList<Tree>();

				for (SingleIndividualSample sample : firstRoundSamples) {

					Tree contractedTree = sample.contractTree(gt);
					contractedTree.rerootTreeAtEdge(GlobalMaps.taxonNameMap
							.getSpeciesIdMapper().getSTTaxonIdentifier()
							.getTaxonName(0));
					Trees.removeBinaryNodes((MutableTree)contractedTree);
					// returns a tree with species label
					firstRoundSampleTrees.add(contractedTree);
				}

				ArrayList<Tree> greedies = new ArrayList<Tree>();
				for (int r = 0; r < secondRoundSampling; r++) {
					List<Tree> sample;

					sample = firstRoundSampleTrees.subList(r*K, K*r+99);
					greedies.add(Factory.instance.greedyCons().greedyConsensus(sample, false,
							GlobalMaps.taxonNameMap.getSpeciesIdMapper()
							.getSTTaxonIdentifier(), true));
				}


				allGreedies[gtindex++]=greedies;
				// + clusters.getClusterCount());

			}
			Logging.logTimeMessage("WQDataCollection 657-660: ");
		}

		/**
		 * generate a list of sampled gene trees selecting each one randomly
		 */

		ArrayList<Tree> baseTrees = new ArrayList<Tree>();
		List<STITreeCluster> STls = new ArrayList<STITreeCluster>();
		for(BitSet b : this.speciesMatrix.inferTreeBitsets()){
			STITreeCluster sti = Factory.instance.newCluster(GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier());
			sti.setCluster(b);
			STls.add(sti);
		}
		Tree ST = Utils.buildTreeFromClusters(STls, GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier(), false, false);

		baseTrees.add(ST);
		addBipartitionsFromSignleIndTreesToX(ST, baseTrees,
				GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier(), true, false); 

		Logging.logTimeMessage(" WQDataCollection 701-704: ");

		secondRoundSampling(secondRoundSampling, allGreedies, baseTrees);

		int prev = 0, gradiant = 0;

		Logging.log("Number of Clusters after addition from gene trees: "+clusters.getClusterCount());

		if (inference.options.getAddExtra() == 0) {
			return;
		}

		if (inference.options.getAddExtra() != 0) {
			this.addExtraBipartitionByDistance();

			Logging.log("Adding to X using resolutions of greedy consensus ...\n");

			for (int l = 0; l < secondRoundSampling; l++) {
				ArrayList<Tree> genes = new ArrayList<Tree>();
				for (int j = 0; j < allGreedies.length; j++) {
					genes.add(allGreedies[j].get(l));
				}
				Logging.log("calculating extra bipartitions to be added at level "
						+ inference.options.getAddExtra() + " ...");
				this.addExtraBipartitionByHeuristics(genes,
						GlobalMaps.taxonNameMap.getSpeciesIdMapper()
						.getSTTaxonIdentifier(),
						inference.options.getPolylimit());

				Logging.log("Number of Clusters after addition by greedy: "
						+ clusters.getClusterCount());
				Logging.logTimeMessage(" WQDataCollection 760-763: ");

				gradiant = clusters.getClusterCount() - prev;
				Logging.log("gradient"+l+" in heuristiic: "+ gradiant);
				prev = clusters.getClusterCount();

			}
		}
	}

	protected void secondRoundSampling(int secondRoundSampling, List<Tree>[] allGreedies,
			ArrayList<Tree> baseTrees) {
		int gradiant = 0;
		int prev = 0;
		for (int ii=0; ii < secondRoundSampling; ii++) {
			for (int j=0 ; j< allGreedies.length ; j++) {

				new FormSetXLoop(allGreedies[j].get(ii), baseTrees).run();

			}
			Logging.log("------------------------------");
			gradiant = clusters.getClusterCount() - prev;
			Logging.log("gradient"+ii +": "+ gradiant);
			prev = clusters.getClusterCount();

		}
	}

	public class FormSetXLoop implements Runnable {
		Tree tree;
		ArrayList<Tree> baseTrees;


		public FormSetXLoop(Tree tree,
				ArrayList<Tree> baseTrees) {
			this.tree = tree;
			this.baseTrees = baseTrees;
		}

		public void run() {
			try {
				addBipartitionsFromSignleIndTreesToX(tree, baseTrees, GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTTaxonIdentifier(), true, false);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}

	protected List<STITree> preProcessTreesBeforeAddingToX(STITree tree) {
		return Arrays.asList( new STITree[]{ tree});
	}
	
	protected Collection<Tree> preProcessTreesBeforeAddingToX(Collection<Tree> trees) {
		return trees;
	}
	
	/**
	 * Calculates a distance matrix based on input gene trees. To be used for
	 * gene tree completion.
	 */
	private void calculateDistances() {

		if (options.isUstarDist()) {
			// Note that matricesByBranchDistance both populates the similarity matrix and returns the speces level matrix. 
			this.geneMatrix = new DistanceMatrix(taxid.taxonCount());

		} else {
			this.geneMatrix = Factory.instance.newSimilarityMatrix(taxid.taxonCount());
		}

		Logging.log("Calculating distance matrix (for completion of X) ....");

		this.speciesMatrix = this.geneMatrix.populate(treeAllClusters, 
				this.originalInompleteGeneTrees,
				GlobalMaps.taxonNameMap.getSpeciesIdMapper());	
		Logging.log("");
		/*
		Logging.log("Calculating quartet distance matrix (for completion of X)");

		this.geneMatrix = Factory.instance.newSimilarityMatrix(
				taxid.taxonCount());
		this.geneMatrix.populateByQuartetDistance(treeAllClusters,
				this.originalInompleteGeneTrees);
		this.speciesMatrix = GlobalMaps.taxonNameMap
				.getSpeciesIdMapper().convertToSpeciesDistance(
						this.geneMatrix);// this.similarityMatrix.convertToSpeciesDistance(spm);
		 */
	}

	/**
	 * Computes the set of available leaves per gene tree.
	 * 
	 * @param inference
	 * @return
	 */
	public int preProcess(AbstractInference<Tripartition> inference) {
		Logging.log("Number of gene trees: "
				+ this.originalInompleteGeneTrees.size());
		// n = taxid.taxonCount();

		int haveMissing = 0;
		ArrayList<Tree> tempCopy = new ArrayList<Tree>(this.originalInompleteGeneTrees);
		this.originalInompleteGeneTrees.clear();
		
		for (Tree tree : tempCopy) {
			this.originalInompleteGeneTrees.add(rearrange(tree));
		}
		
		for (Tree tree : this.originalInompleteGeneTrees) {
			if (tree.getLeafCount() != taxid.taxonCount()) {
				haveMissing++;
			}
			//System.out.println(tree);
			//rearrange(tree);
			
			//System.out.println(tree);
			//System.out.println("------");
			
			Stack<STITreeCluster> stack = new Stack<STITreeCluster>();
			for (TNode n: tree.postTraverse()) {
				STINode node = (STINode) n;
				if (node.isLeaf()) {
					String nodeName = node.getName(); //GlobalMaps.TaxonNameMap.getSpeciesName(node.getName());

					STITreeCluster cluster = Factory.instance.newCluster(taxid);
					Integer taxonID = taxid.taxonId(nodeName);
					cluster.addLeaf(taxonID);

					stack.add(cluster);
					node.setData(cluster);

				} else {
					ArrayList<STITreeCluster> childbslist = new ArrayList<STITreeCluster>();
					BitSet bs = new BitSet(taxid.taxonCount());
					for (TNode child: n.getChildren()) {
						STITreeCluster pop = stack.pop();
						childbslist.add(pop);
						bs.or(pop.getBitSet());
					}

					STITreeCluster cluster = Factory.instance.newCluster(taxid,(BitSet) bs.clone());

					//((STINode)node).setData(new GeneTreeBitset(node.isRoot()? -2: -1));
					stack.add(cluster);
					node.setData(cluster);
				}
			}
			String[] gtLeaves = tree.getLeaves();
			STITreeCluster gtAll = Factory.instance.newCluster(taxid);
			long ni = gtLeaves.length;
			for (int i = 0; i < ni; i++) {
				gtAll.addLeaf(taxid.taxonId(gtLeaves[i]));
			}
			treeAllClusters.add(gtAll);
		}
		
		Logging.log(haveMissing + " trees have missing taxa");

		return haveMissing;
	}


	private void reroot(Tree tr) {
		List<STINode> children = new ArrayList<STINode>();
		int n = tr.getLeafCount()/2;
		int dist = n;
		TNode newroot = tr.getRoot();
		for (TNode node : tr.postTraverse()) {
			if (!node.isLeaf()) {                        
				if (Math.abs(n - node.getLeafCount()) < dist) {
					newroot = node;
					dist = n - node.getLeafCount();
				}
			}
		}
		for (STINode child: children) {
			STINode snode = child.getParent();
			snode.removeChild((TMutableNode) child, false);
			TMutableNode newChild = snode.createChild(child);
			if (child == newroot) {
				newroot = newChild;
			}
		}
		if (newroot != tr.getRoot())
			((STITree)(tr)).rerootTreeAtEdge(newroot);


	}
	
	class NodeHeight implements Comparable<NodeHeight>{
		TNode node;
		int height;
		public NodeHeight(TNode node, int height) {
			super();
			this.node = node;
			this.height = height;
		}
		@Override
		public int compareTo(NodeHeight o) {
			if (node == o.node)
				return 0;
			else if (height == o.height)
				return this.node.getID() > (o.node.getID()) ? 1 : -1;
			else if (height > o.height)
				return 1;
			else return -1;
		} 
		
	}
	private Tree rearrange(Tree tr) {
		Stack<NodeHeight> stack = new Stack<NodeHeight>();
		ArrayList<TreeSet<NodeHeight>> swaps = new ArrayList<TreeSet<NodeHeight>>();
		int diameter = 0;
		TNode newroot = tr.getRoot();
		for (TNode node : tr.postTraverse()) {
			if (node.isLeaf()) {  
				stack.push(new NodeHeight(node, 0));
			} else {
				int myh = -1;
				TreeSet<NodeHeight> chordered = new TreeSet<NodeHeight>();
				boolean swap = false;
				for (TNode child : node.getChildren()) {
					NodeHeight pop = stack.pop();
					chordered.add(pop);
					if (pop.height > myh) {
						if (myh != -1)
							swap = true;
						myh = pop.height;
					}
				}
				Iterator<NodeHeight> i = chordered.descendingIterator();

				int myd = i.next().height + i.next().height + 2;
				if (myd > diameter) {
					diameter = myd;
				}
				if (swap) {
					swaps.add(chordered);
				}
				stack.push(new NodeHeight(node, myh+1));
			}
		}
		for (TreeSet<NodeHeight> l: swaps) {
			sawpChildren(l);
		}
		stack = new Stack<NodeHeight>();
		int diff = diameter;
		for (TNode node : tr.postTraverse()) {
			if (node.isLeaf()) {  
				stack.push(new NodeHeight(node, 0));
			} else {
				int myh = -1;
				for (TNode child : node.getChildren()) {
					NodeHeight pop = stack.pop();
					if (pop.height > myh) {
						myh = pop.height;
					}
				}				
				myh++;
				stack.push(new NodeHeight(node, myh));
			
				if ( Math.abs(diameter/2 - myh) < diff) {
					diff = Math.abs(diameter/2 - myh);
					newroot = node;
				}
			}
		}
		//System.out.println(tr);
		if (newroot != tr.getRoot() && newroot.getParent() != tr.getRoot()) {
			//System.out.println("   " +tr);
			((STITree)(tr)).rerootTreeAtEdge(newroot);
			Iterable<? extends TNode> children = tr.getRoot().getChildren();
			double tl = 0;
			for (TNode child: children)
				if (child.getParentDistance() >= 0 )
					tl += child.getParentDistance();
			for (TNode child: children)
				child.setParentDistance(tl/(tr.getRoot().getChildCount()+0.0));
			//System.out.println(tr);
		}
		try {
			//System.err.println(tr);
			tr = new STITree(tr.toNewickWD());
			//System.out.println(tr);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		//System.out.println("----");
		
		return tr;
		/*for (TNode node : tr.postTraverse()) {
			if (node.isLeaf()) {  
				stack.push(new NodeHeight(node, 0));
			} else {
				int myh = -1;
				for (TNode child : node.getChildren()) {
					NodeHeight pop = stack.pop();
					if (pop.height > myh) {
						myh = pop.height;
					}
					System.err.print(pop.height+" ");
				}				
				myh++;
				stack.push(new NodeHeight(node, myh));
				System.err.print("  ,   ");	
			}
		}
		System.err.println("");*/
	}
	
	void sawpChildren(TreeSet<NodeHeight> children) {
		STINode node = (STINode) children.first().node.getParent();
		for (NodeHeight childP : children) {
			STINode child = (STINode) childP.node;
			STINode temp;
			try {
				temp = new STITree(";").getRoot();
				//double l = child.getParentDistance();
				TMutableNode c = createChildWithLength(temp,child);
				child.removeNode();
				createChildWithLength(node,c);
				//c = node.createChild((TMutableNode) temp.getChildren().iterator().next());
				//c.setParentDistance(l);
			} catch (IOException | ParseException e) {
				System.err.println(e);
			}
		}
		//System.err.println("----" + node);
	}
	
	STINode createChildWithLength(STINode parent, TNode clade) {
        STINode node = parent.createChild(clade.getName());
        if(((STINode)clade).getData()!=null){
                node.setData(((STINode)clade).getData());
        }
        node.setParentDistance(clade.getParentDistance());

        for(TNode child : clade.getChildren()) {
        	createChildWithLength(node,child);
        }

        return node;
	}

	/*
	 * long maxPossibleScore(Tripartition trip) {
	 * 
	 * long weight = 0;
	 * 
	 * for (STITreeCluster all : this.treeAllClusters){ long a =
	 * trip.cluster1.getBitSet().intersectionSize(all.getBitSet()), b =
	 * trip.cluster2.getBitSet().intersectionSize(all.getBitSet()), c =
	 * trip.cluster3.getBitSet().intersectionSize(all.getBitSet());
	 * 
	 * weight += (a+b+c-3)*a*b*c; } return weight; }
	 */

	/**
	 * Completes all the gene trees using a heuristic algorithm described in
	 * Siavash's dissertation. Uses the distance matrix for completion.
	 * @param haveMissing 
	 */
	protected void completeGeneTrees(boolean completionNecessary) {
		
	
		if (completionNecessary) {
			Logging.log("Will attempt to complete bipartitions from X before adding using a distance matrix.");
		}
		int t = 0;
		BufferedWriter completedFile = null;
		if (this.options.isOutputCompletedGenes()) {
			String fn = this.options.getOutputFile() + ".completed_gene_trees";
			Logging.log("Outputting completed gene trees to " + fn);
			try {
				completedFile = new BufferedWriter(new FileWriter(fn));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		
		BufferedWriter cFile = null;
		if (this.options.isOutputCompatibledGenes()) {
		
			String fn = options.getOutputFile() + ".compatibled_gene_trees";
			Logging.log("Outputting compatible gene trees to " + fn);
			try {
				cFile = new BufferedWriter(new FileWriter(fn));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		

		for (Tree tt : this.originalInompleteGeneTrees) {
			STITree ot = new STITree<>(tt);
			// 1. Make trees compatible
			for (STITree tr: preProcessTreesBeforeAddingToX((STITree) ot)) {
				if (cFile != null) {
					try {
						cFile.write(tr.toNewick() + " \n");
						cFile.flush();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
				
				Tree trc;
				if (completionNecessary) {
					// 2. Make trees Complete
					trc = getCompleteTree(tr, this.treeAllClusters.get(t).getBitSet());
					if (completedFile != null) {
						try {
							completedFile.write(trc.toNewick() + " \n");
							completedFile.flush();
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
					}
				} else {
					trc = new STITree(tr);
				}
				this.compatibleCompleteGeneTrees.add(trc);
			}
			t = t+1;
		}
		if (completedFile != null) {
			try {
				completedFile.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			if (!options.isRunSearch()) {
				System.err.println("Stopping after outputting completed gene tree");
				System.exit(0);
			}
		}
		
		if (cFile != null) {
			try {
				cFile.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		
		if (options.isCompatibleNorun())
			System.exit(0);
	}

	/**
	 * for debugging
	 * 
	 * @param distSTMatrix
	 */
	private void printoutdistmatrix(double[][] distSTMatrix) {
		SpeciesMapper spm = GlobalMaps.taxonNameMap.getSpeciesIdMapper();
		for (String s : spm.getSTTaxonIdentifier().getAllTaxonNames()) {
			System.err.print(String.format("%1$8s", s));
		}
		Logging.log("");
		for (int i = 0; i < spm.getSpeciesCount(); i++) {
			for (int j = 0; j < spm.getSpeciesCount(); j++) {
				System.err.print(String.format("%1$8.3f", distSTMatrix[i][j]));
			}
			Logging.log("");
		}
	}

	/**
	 * By default (when SLOW is false) it only computes an UPGMA tree from the
	 * distance data and adds to the set of bipartitions
	 */
	public void addExtraBipartitionByDistance() {

		for (BitSet bs : speciesMatrix.inferTreeBitsets()) {
			STITreeCluster g = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
					.getGeneClusterForSTCluster(bs);
			this.addCompletedSpeciesFixedBipartionToX(g,
					g.complementaryCluster());
		}
		if (SLOW) {
			for (BitSet bs : speciesMatrix.getQuadraticBitsets()) {
				STITreeCluster g = GlobalMaps.taxonNameMap.getSpeciesIdMapper()
						.getGeneClusterForSTCluster(bs);
				this.addCompletedSpeciesFixedBipartionToX(g,
						g.complementaryCluster());
			}
		}

		Logging.log("Number of Clusters after addition by distance: "
				+ clusters.getClusterCount());
	}

	protected boolean shouldDoQuadratic(int th, TNode greedyNode, int j) {
		boolean quadratic = (this.SLOW
				|| (th < this.GREEDY_DIST_ADDITTION_LAST_THRESHOLD_INDX 
						&& j < this.GREEDY_ADDITION_DEFAULT_RUNS)) 
				&& greedyNode.getChildCount() <= polytomySizeLimit;
		return quadratic;
	}
	
	protected void addFromConsensusTreesToX(Collection<Tree> allGreedies) {
		// We don't need to do anything here
		// because bipartitions of greedy consensus trees have
		// already been added to the search space by construction
		
	}
	
	protected Collection<Tree> prepareConsensusTrees(Collection<Tree> contractedTrees, TaxonIdentifier tid,
			int polylimit) {
		// Greedy trees. These will be based on sis taxon identifier
		Collection<Tree> allGreedies;

		Logging.log("Computing greedy consensus  ");
		long t = System.currentTimeMillis();
		Logging.log("Adding to X using resolutions of greedy consensus ...");
		for (Tree tree : contractedTrees) {
			tree.rerootTreeAtEdge(tid.getTaxonName(0));
			Trees.removeBinaryNodes((MutableTree) tree);
		}

		/*
		 * if (completeTrees.size() < 2) {
		 * Logging.log("Only "+completeTrees.size() +
		 * " complete trees found. Greedy-based completion not applicable.");
		 * return; }
		 */
		allGreedies = Factory.instance.greedyCons().greedyConsensus(contractedTrees,
				this.GREEDY_ADDITION_THRESHOLDS, true, 1, tid, true);
			
		allGreedies = preProcessTreesBeforeAddingToX(allGreedies);
			
		int sumDegrees = 0;

		Logging.log("took "+ ((System.currentTimeMillis()-t)/1000+" seconds"));

		ArrayList<Integer> deg = new ArrayList<Integer>();
		for (Tree cons : allGreedies) {			
			for (TNode greedyNode : cons.postTraverse()) {
				if (greedyNode.getChildCount() > 2){
					deg.add(greedyNode.getChildCount());
				}
			}
		}	
		Collections.sort(deg);

		if(polylimit == -1){

			int N = this.GREEDY_ADDITION_MAX_POLYTOMY_MIN
					+ GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSpeciesCount()
					* this.GREEDY_ADDITION_MAX_POLYTOMY_MULT;
			Logging.log("Limit for sigma of degrees:" + N + "\n");
			int i = 0;
			while(sumDegrees < N && i < deg.size()){
				sumDegrees += Math.pow(deg.get(i),2);
				i++;
			}

			if(i > 0)
				polytomySizeLimit = deg.get(i-1);
			else    
				polytomySizeLimit = 3; // this means that the tree is fully binary

		}
		else
			polytomySizeLimit = polylimit;

		Logging.log("polytomy size limit : > " + polytomySizeLimit);
		Logging.log("discarded polytomies: ");
		for(int d: deg){
			if(d > polytomySizeLimit)
				Logging.log(d+" ");
		}
		Logging.log("All polytomy sizes: "+deg);
		return allGreedies;
	}
	
	/**
	 * Main function implementing new heuristics in ASTRAL-II. At this point, we
	 * require a subsample with a single individual per species.
	 * 
	 * @param trees
	 *            : the input trees contracted to the subsample
	 * @param sis
	 *            : the single-individual subsample information
	 * @return 
	 */
	ArrayList addExtraBipartitionByHeuristics(Collection<Tree> contractedTrees,
			TaxonIdentifier tid, int polylimit) {

		Collection<Tree> allGreedies = prepareConsensusTrees(contractedTrees, tid, polylimit);
		int th = 0;
		//int max= 0;
		/**
		 * For each greedy consensus tree, use it to add extra bipartitions to
		 * the tree.
		 */
		ArrayList stringOutput = new ArrayList();
		for (Tree cons : allGreedies) {
			double thresh = this.GREEDY_ADDITION_THRESHOLDS[th];
			myLog("Threshold " + thresh + ":" + "",stringOutput);

			addFromConsensusTreesToX(allGreedies);
			
			for (TNode greedyNode : cons.postTraverse()) {

				if (greedyNode.isLeaf() || greedyNode.getChildCount() <= 2) {
					continue;
				}

				myLog(invokeRunner(new addExtraBipartitionByHeuristicsLoop(
						greedyNode, tid, th, contractedTrees)), stringOutput);
			}
			th = (th + 1) % this.GREEDY_ADDITION_THRESHOLDS.length;
		}
		return stringOutput;
	}

	protected void myLog(Object res, ArrayList stringOutput) {
		Logging.log(res.toString());
	}

	protected Object invokeRunner(addExtraBipartitionByHeuristicsLoop callable) {
		return callable.call();
	}


	public class addExtraBipartitionByHeuristicsLoop implements Callable {
		TNode greedyNode;
		TaxonIdentifier tid;
		int th;
		Collection<Tree> contractedTrees;

		public addExtraBipartitionByHeuristicsLoop(TNode greedyNode,
				TaxonIdentifier tid, int th,
				Collection<Tree> contractedTrees) {
			this.greedyNode = greedyNode;
			this.tid = tid;
			this.th = th;
			this.contractedTrees = contractedTrees;
		}

		public String call() {

			String ret;
			BitSet greedyBS = (BitSet) ((STITreeCluster) ((STINode) greedyNode)
					.getData()).getBitSet();

			BitSet[] childbs = new BitSet[greedyNode.getChildCount() + 1];
			int i1 = 0;
			for (TNode c : greedyNode.getChildren()) {
				childbs[i1] = (BitSet) ((STITreeCluster) ((STINode) c)
						.getData()).getBitSet();
				i1++;
			}
			// Compute the complementary cluster of the cluster for this
			// node
			// recall: a greedy consensus is defined on unrooted trees and
			// we are treating the tree as rooted here. Computing
			// the complementary cluster and adding it to the child list
			// in effect makes the tree unrooted again
			BitSet comp = (BitSet) greedyBS.clone();
			comp.flip(0, tid.taxonCount());
			childbs[i1] = comp;

			ret = "polytomy of size " + greedyNode.getChildCount();

			// First resolve the polytomy using distances.
			WQDataCollection.this.addSubSampledBitSetToX(
					((SimilarityMatrix) WQDataCollection.this.speciesMatrix).resolveByUPGMA(
							Arrays.asList(childbs), true), tid);

			// Resolve by subsampling the greedy.
			// Don't get confused. We are not subsampling species
			// in a greedy consensus tree, which itself, subsamples one
			// individual per species.
			int k = 0;

			for (int j = 0; j < GREEDY_ADDITION_DEFAULT_RUNS + k; j++) {

				boolean quadratic = shouldDoQuadratic(th, greedyNode, j); 

				if (sampleAndResolve(childbs, contractedTrees, quadratic, tid,true, false) && k < GREEDY_ADDITION_MAX) {
					k += GREEDY_ADDITION_IMPROVEMENT_REWARD;

				}
			}
			ret += "; rounds with additions with at least "
					+ GREEDY_ADDITION_MIN_FREQ + " support: " + k
					/ GREEDY_ADDITION_IMPROVEMENT_REWARD
					+ "; clusters: " + clusters.getClusterCount() ;
			return ret;

		}
	}

	int arrayListMax(ArrayList<Integer> input){
		if(input.size()==0)
			return 0;
		else
			return Collections.max(input);
	}
	int arrayListMin(ArrayList<Integer> input){
		if(input.size()==0)
			return 0;
		else
			return Collections.min(input);
	}

	/**
	 * This is the first step of the greedy algorithm where one counts how many
	 * times a bitset is present in input gene trees. A complication is that
	 * this is computing the greedy consensus among the gene trees subsampled to
	 * the given randomSample
	 * 
	 * @param genetrees
	 * @param randomSample
	 * @return
	 */
	private HashMap<BitSet, Integer> returnBitSetCounts(Collection<Tree> genetrees,
			HashMap<String, Integer> randomSample) {

		HashMap<BitSet, Integer> counts = new HashMap<BitSet, Integer>();

		for (Tree gt : genetrees) {
			List<BitSet> bsList = Utils.getBitsets(randomSample, gt);

			for (BitSet bs : bsList) {
				if (counts.containsKey(bs)) {
					counts.put(bs, counts.get(bs) + 1);
					continue;
				}
				BitSet bs2 = (BitSet) bs.clone();
				bs2.flip(0, randomSample.size());
				if (counts.containsKey(bs2)) {
					counts.put(bs2, counts.get(bs2) + 1);
					continue;
				}
				counts.put(bs, 1);
			}
		}
		return counts;
	}

	/**
	 * For a given polytomy, samples randomly around its branches and adds
	 * results to the set X.
	 * 
	 * @param polytomyBSList
	 * @param addQuadratic
	 * @return Whether any clusters of high frequency were added in this round
	 */
	protected boolean sampleAndResolve(BitSet[] polytomyBSList,
			Collection<Tree> inputTrees, boolean addQuadratic,
			TaxonIdentifier tid, boolean addByDistance, 
			boolean forceResolution) {

		boolean addedHighFreq = false;
		// random sample taxa
		HashMap<String, Integer> randomSample = randomSampleAroundPolytomy(
				polytomyBSList, tid);

		addedHighFreq = resolveLinearly(polytomyBSList, inputTrees,
				randomSample, tid, forceResolution);
		if(addByDistance)
			resolveByDistance(polytomyBSList, randomSample, addQuadratic,
					tid);

		return addedHighFreq;
	}

	/**
	 * Resolves a polytomy using the greedy consensus of a subsample from
	 * clusters around it
	 * 
	 * @param polytomyBSList
	 * @param randomSample
	 * @return
	 */
	protected boolean resolveLinearly(BitSet[] polytomyBSList, 
			Collection<Tree> inputTrees, HashMap<String, Integer> randomSample,
			TaxonIdentifier tid, boolean forceresolution) {
		int sampleSize = randomSample.size();
		// get bipartition counts in the induced trees//******************************************		
		HashMap<BitSet, Integer> counts = returnBitSetCounts(
				inputTrees, randomSample);

		// sort bipartitions
		TreeSet<Entry<BitSet, Integer>> countSorted = new TreeSet<Entry<BitSet, Integer>>(
				new Utils.BSComparator(true, sampleSize));
		countSorted.addAll(counts.entrySet());

		// build the greedy tree
		MutableTree greedyTree = new STITree<BitSet>();
		TNode[] tmpnodes = new TNode[sampleSize];
		for (int i = 0; i < sampleSize; i++) {
			tmpnodes[i] = greedyTree.getRoot().createChild(i + "");
			BitSet bs = new BitSet(sampleSize);
			bs.set(i);
			((STINode<BitSet>) tmpnodes[i]).setData(bs);
		}

		boolean added = false;
		boolean addedHighFreq = false;
		List<BitSet> newBSList = new ArrayList<BitSet>();

		for (Entry<BitSet, Integer> entry : countSorted) {

			BitSet newbs = entry.getKey();

			SchieberVishkinLCA lcaFinder = new SchieberVishkinLCA(greedyTree);
			Set<TNode> clusterLeaves = new HashSet<TNode>();
			TNode node;
			for (int i = newbs.nextSetBit(0); i >= 0; i = newbs
					.nextSetBit(i + 1)) {
				node = tmpnodes[i];
				clusterLeaves.add(node);
			}
			TNode lca = lcaFinder.getLCA(clusterLeaves);
			LinkedList<TNode> movedChildren = new LinkedList<TNode>();
			int nodes = clusterLeaves.size();
			for (TNode child : lca.getChildren()) {
				BitSet childCluster = ((STINode<BitSet>) child).getData();

				BitSet temp = (BitSet) childCluster.clone();
				temp.and(newbs);
				if (temp.equals(childCluster)) {
					movedChildren.add(child);
					nodes -= temp.cardinality();
				}

			}

			// boolean isPartOfGreedy = false;
			if (movedChildren.size() != 0 && nodes == 0) {

				STINode<BitSet> newChild = ((STINode<BitSet>) lca)
						.createChild();
				newChild.setData(newbs);

				while (!movedChildren.isEmpty()) {
					newChild.adoptChild((TMutableNode) movedChildren.get(0));
					movedChildren.remove(0);
				}

				if (addDoubleSubSampledBitSetToX(polytomyBSList, newbs, tid)) {
					if (GREEDY_ADDITION_MIN_RATIO <= (entry.getValue() + 0.0)
							/ inputTrees.size() 
							&& entry.getValue() > GREEDY_ADDITION_MIN_FREQ) {
						addedHighFreq = true;
					}
					added = true;
				}

				/*
				 * if ((GREEDY_ADDITION_MIN_FREQ <=
				 * (entry.getValue()+0.0)/this.completedGeeneTrees.size()) &&
				 * spm.isPerfectGTBitSet(newbs)) { if
				 * (this.addSingleIndividualBitSetToX(newbs)){ addedHighFreq =
				 * true; added = true; } } else{ newBSList.add(newbs); }
				 */
			}

		}

		if (forceresolution || added) {
			for (TNode node : greedyTree.postTraverse()) {
				if (node.getChildCount() < 3) {
					continue;
				}
				ArrayList<BitSet> children = new ArrayList<BitSet>(
						node.getChildCount() + 1);
				BitSet rest = new BitSet(sampleSize);
				for (TNode child : node.getChildren()) {
					children.add(((STINode<BitSet>) child).getData());
					rest.or(((STINode<BitSet>) child).getData());
				}
				rest.flip(0, sampleSize);
				if (rest.cardinality() != 0)
					children.add(rest);

				// addSubSampledBitSetToX(polytomyBSList,
				// this.similarityMatrix.resolveByUPGMA(children, true));
				// //TODO: addback

				while (children.size() > 2) {
					BitSet c1 = children.remove(GlobalMaps.random
							.nextInt(children.size()));
					BitSet c2 = children.remove(GlobalMaps.random
							.nextInt(children.size()));

					BitSet newbs = (BitSet) c1.clone();
					newbs.or(c2);
					addDoubleSubSampledBitSetToX(polytomyBSList, newbs, tid);
					children.add(newbs);
				}
			}
		}

		// this.addSubSampledBitSetToX(polytomyBSList, newBSList);

		return addedHighFreq;
	}

	protected boolean resolveByDistance(BitSet[] polytomyBSList,
			HashMap<String, Integer> randomSample, boolean quartetAddition,
			TaxonIdentifier tid) {
		boolean added = false;

		SimilarityMatrix sampleSimMatrix = (SimilarityMatrix) this.speciesMatrix.getInducedMatrix(randomSample,tid);

		added |= this.addDoubleSubSampledBitSetToX(polytomyBSList,
				sampleSimMatrix.inferTreeBitsets(), tid);

		if (quartetAddition) {
			added |= this.addDoubleSubSampledBitSetToX(polytomyBSList,
					sampleSimMatrix.getQuadraticBitsets(), tid);
		}
		return added;
	}

	protected HashMap<String, Integer> randomSampleAroundPolytomy(
			BitSet[] polyTomy, TaxonIdentifier id) {
		HashMap<String, Integer> randomSample = new HashMap<String, Integer>();
		int ind = 0;
		for (BitSet child : polyTomy) {
			int sample = GlobalMaps.random.nextInt(child.cardinality());
			int p = child.nextSetBit(0);
			for (int i = 0; i < sample; i++) {
				p = child.nextSetBit(p + 1);
			}
			randomSample.put(id.getTaxonName(p), ind);
			ind++;
		}
		return randomSample;
	}

	private boolean addDoubleSubSampledBitSetToX(BitSet[] childbs,
			BitSet restrictedBitSet, TaxonIdentifier tid) {
		BitSet stnewBS = addbackAfterSampling(childbs, restrictedBitSet, tid);
		return this.addSpeciesBitSetToX(stnewBS);
	}

	boolean addSubSampledBitSetToX(
			Iterable<BitSet> restrictedBitSetList, TaxonIdentifier tid) {
		boolean added = false;
		for (BitSet restrictedBitSet : restrictedBitSetList) {
			added |= this.addSpeciesBitSetToX(restrictedBitSet);
		}
		return added;
	}

	protected boolean addDoubleSubSampledBitSetToX(BitSet[] childbs,
			Iterable<BitSet> restrictedBitSetList, TaxonIdentifier tid) {
		boolean addded = false;
		for (BitSet restrictedBitSet : restrictedBitSetList) {
			addded |= addDoubleSubSampledBitSetToX(childbs, restrictedBitSet,
					tid);
		}
		return addded;
	}

	private BitSet addbackAfterSampling(BitSet[] childbs,
			BitSet restrictedBitSet, TaxonIdentifier tid) {
		BitSet newbs = new BitSet(tid.taxonCount());
		for (int j = restrictedBitSet.nextSetBit(0); j >= 0; j = restrictedBitSet
				.nextSetBit(j + 1)) {
			newbs.or(childbs[j]);
		}
		return newbs;
	}



	private void resolveByUPGMA(MutableTree tree, TaxonIdentifier ti,
			SimilarityMatrix sm) {
		Stack<BitSet> stack = new Stack<BitSet>();
		for (TNode node : tree.postTraverse()) {
			BitSet bitset = new BitSet(ti.taxonCount());
			if (node.isLeaf()) {
				bitset.set(ti.taxonId(node.getName()));
			} else {
				List<TMutableNode> children = new ArrayList<TMutableNode>();
				ArrayList<BitSet> poly = new ArrayList<BitSet>();
				for (TNode child : node.getChildren()) {
					BitSet cbs = stack.pop();
					poly.add(cbs);
					children.add((TMutableNode) child);
					bitset.or(cbs);

				}
				if (children.size() > 2) {
					for (BitSet bs : sm.resolveByUPGMA(poly, false)) {
						TMutableNode newChild = ((TMutableNode) node)
								.createChild();
						for (int i = bs.nextSetBit(0); i >= 0; i = bs
								.nextSetBit(i + 1)) {
							TMutableNode child = children.get(i);
							if (child.getParent() == node) {
								newChild.adoptChild(child);
							}
							children.set(i, newChild);
						}
					}
				}
			}
			stack.push(bitset);
		}
	}

	//	public Object clone() throws CloneNotSupportedException {
	//		WQDataCollection clone = (WQDataCollection) super.clone();
	//		clone.clusters = (this.clusters).clone();
	//		return clone;
	//	}

	/*
	 * private BitSet hemogenizeBipartitionByVoting(BitSet b1copy, BitSet
	 * b2copy) {
	 * 
	 * Find out for each species whether they are more frequent in left or right
	 * 
	 * int [] countsC1c = new int [spm.getSpeciesCount()], countsC2c = new int
	 * [spm.getSpeciesCount()]; for (int i = b1copy.nextSetBit(0); i >=0 ; i =
	 * b1copy.nextSetBit(i+1)) { int sID = spm.getSpeciesIdForTaxon(i);
	 * countsC1c[sID]+=10; if (spm.getLowestIndexIndividual(sID) == i ) {
	 * countsC1c[sID]++; } } for (int i = b2copy.nextSetBit(0); i >=0 ; i =
	 * b2copy.nextSetBit(i+1)) { int sID = spm.getSpeciesIdForTaxon(i);
	 * countsC2c[sID]+=10; if (spm.getLowestIndexIndividual(sID) == i ) {
	 * countsC2c[sID]++; } }
	 *//**
	 * Add a bipartition where every individual is moved to the side where it
	 * is more common
	 */
	/*
	 * BitSet gtbs1 = new BitSet(spm.getSpeciesCount()); for (int i = 0; i <
	 * countsC2c.length; i++) { if (countsC1c[i] > countsC2c[i]) { gtbs1.set(i);
	 * } } return gtbs1; }
	 */
	

}
