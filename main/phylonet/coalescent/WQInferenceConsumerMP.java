package phylonet.coalescent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import phylonet.coalescent.IClusterCollection.VertexPair;
import phylonet.tree.model.TNode;
import phylonet.tree.model.Tree;
import phylonet.tree.model.sti.STINode;
import phylonet.tree.model.sti.STITreeCluster;
import phylonet.tree.model.sti.STITreeCluster.Vertex;
import phylonet.tree.model.sti.STITreeClusterMP.VertexMP;



public class WQInferenceConsumerMP extends WQInference {


	static TaxonIdentifierMP taxid = (TaxonIdentifierMP) GlobalMaps.taxonIdentifier;

	AtomicInteger processCount = new AtomicInteger();
	Object locks = new Object();

	public WQInferenceConsumerMP(Options inOptions, List<Tree> trees, List<Tree> extraTrees, List<Tree> toRemoveExtraTrees) {
		super(inOptions, trees, extraTrees, toRemoveExtraTrees);
		GlobalQueues.instance.setQueueWeightResults(new LinkedBlockingQueue<Long>());
		GlobalQueues.instance.setQueueClusterResolutions(new LinkedBlockingQueue<Iterable<VertexPair>>());

		this.forceAlg = inOptions.getAlg();

	}


	/**
	 * This method first computes the quartet scores and then calls
	 * scoreBranches to annotate branches (if needed). 
	 * The method assumes the input tree st has labels of individuals (not species). 
	 */
	public double scoreSpeciesTreeWithGTLabels(Tree st, boolean initialize) {

		if (initialize) {
			initForScoring();
		}

		Stack<STITreeCluster> stack = new Stack<STITreeCluster>();
		long sum = 0l;
		boolean poly = false;

		((WQWeightCalculatorMP)weightCalculator).setThreadingOff(true);
		List<Future<Long[]>> weights = new ArrayList<Future<Long[]>>();
		final Tripartition [] tripartitionBatch = new Tripartition[Polytree.PTNative.batchSize];
		int batchPosition = 0;
		for (TNode node: st.postTraverse()) {
			if (node.isLeaf()) {
				handleLeaf(stack, node);

			} else {
				ArrayList<STITreeCluster> childbslist = handleInternalNode(stack, node);
				if (childbslist == null) {
					poly = true;
					continue;
				}


				for (int i = 0; i < childbslist.size(); i++) {
					for (int j = i+1; j < childbslist.size(); j++) {
						for (int k = j+1; k < childbslist.size(); k++) {
							final Tripartition trip = new Tripartition(childbslist.get(i), childbslist.get(j), childbslist.get(k));
							tripartitionBatch[batchPosition++] = trip;

							if (batchPosition == tripartitionBatch.length) {
								final Tripartition[] arrayCopy = Arrays.copyOfRange(tripartitionBatch, 0, batchPosition);
								Future<Long[]> s = Threading.submit(new Callable<Long[]>() {

									@Override
									public Long[] call() throws Exception {
										return weightCalculator.calculateWeight(arrayCopy);
									}

								});
								weights.add(s);
								batchPosition = 0;
							}
						}
					}					       
				}
			}

		}
		if (batchPosition != 0) {

			final Tripartition[] arrayCopy = Arrays.copyOfRange(tripartitionBatch, 0, batchPosition);
			Future<Long[]> s = Threading.submit(new Callable<Long[]>() {

				@Override
				public Long[] call() throws Exception {
					return weightCalculator.calculateWeight(arrayCopy);
				}

			});
			weights.add(s);
		}

		//TreeSet<Long> c = new TreeSet<>();
		for (Future<Long[]> ws: weights) {
			try {
				for (Long w : ws.get()) {
					sum += w;
					//c.add(w);
				}
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
		//System.err.println(c);
		return logAndSet(st, sum, poly);

	}


	/**
	 * Annotates the species tree branches with support, branch length, etc. 
	 * @param st
	 * @return
	 */
	@Override
	protected NodeData[] scoreBranches(Tree st) {
	
		NodeData[] nodeList = super.scoreBranches(st);
		try {
			synchronized(locks) {
				while(processCount.get() != 0) {
					locks.wait();
				}
			}
		}
		catch(InterruptedException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		
		return nodeList;
	}


	
	@Override
	public void scoreBranches(BipartitionWeightCalculator weightCalculator2, NodeData[] nodeDataList, int ni,
			STINode node, STITreeCluster cluster, STITreeCluster c1, STITreeCluster c2, long cs) {
		processCount.incrementAndGet();
		Threading.execute(scoreBranchesLoop(weightCalculator2, nodeDataList, ni, node, cluster, c1, c2, cs, processCount, locks));
	}


	public scoreBranchesLoop scoreBranchesLoop(BipartitionWeightCalculator weightCalculator2,NodeData [] nodeDataList, int i, STINode node,
			 STITreeCluster cluster, STITreeCluster c1, STITreeCluster c2, long cs, AtomicInteger processCount, Object lock) {
		return new scoreBranchesLoop(weightCalculator2, nodeDataList, i, node, cluster, c1, c2, cs, processCount, lock);
	}

	public class scoreBranchesLoop extends phylonet.coalescent.WQInference.scoreBranchesLoop implements Runnable {
		AtomicInteger processCount;
		Object lock;
		public scoreBranchesLoop(BipartitionWeightCalculator weightCalculator2, NodeData []  nodeDataList, int i, STINode node,
				STITreeCluster cluster, STITreeCluster c1, STITreeCluster c2, long cs, AtomicInteger processCount, Object lock) {
			super(weightCalculator2, nodeDataList, i, node, cluster, c1, c2, cs);
			this.lock = lock;
			this.processCount = processCount;
		}
		public void run() {

			this.compute();
			processCount.decrementAndGet();
			synchronized(lock) {
				lock.notify();
			}
		}

	}

	@Override
	public AbstractComputeMinCostTask<Tripartition> newComputeMinCostTask(
			AbstractInference<Tripartition> inference, Vertex all, IClusterCollection clusters) {
		return new WQComputeMinCostTaskConsumer( inference, (VertexMP) all);
	}


	public IClusterCollection newClusterCollection() {
		return new HashClusterCollection(taxid.taxonCount());
	}

	@Override
	public AbstractWeightCalculator<Tripartition> newWeightCalculator() {
		return new WQWeightCalculatorMP(this, GlobalQueues.instance.getQueueWeightResults());

	}

	@Override
	public AbstractWeightCalculator<Tripartition> newWeightCalculatorForWeigth() {
		AbstractWeightCalculator wc =  newWeightCalculator();
		((WQWeightCalculatorMP)wc).setThreadingOff(true);
		return wc;
	}

	protected int[] getGeneTreesAsInt() {
		return ((WQWeightCalculatorMP)this.weightCalculator).geneTreesAsInts();
	}

	@Override
	public void setupMisc() {

		this.maxpossible = this.calculateMaxPossible();
		Logging.log("Number of quartet trees in the gene trees: " +
				this.maxpossible);
		((HashClusterCollection)this.dataCollection.clusters).preComputeHashValues();


		final WQInferenceProducer inferenceProducer = 
				new WQInferenceProducer(this);
		inferenceProducer.setup();

		final TurnTaskToScores weightDistributor = new TurnTaskToScores(this, inferenceProducer.getQueueReadyTripartitions());

		Thread producer = new Thread(new Runnable() {

			public void run() {
				inferenceProducer.inferSpeciesTree();
				try {
					inferenceProducer.getQueueReadyTripartitions().put(TurnTaskToScores.POISON_PILL);
				}
				catch (Exception e) {
				}
				//weightDistributor.done = true;
			}
		});
		producer.setPriority(Thread.MAX_PRIORITY);
		producer.start();
		try {
			Thread.sleep(1000); // Meant to give a bit of head-start to the producer
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		(new Thread(weightDistributor)).start();

	}

}
