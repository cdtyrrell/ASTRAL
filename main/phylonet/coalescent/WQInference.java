package phylonet.coalescent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;

import phylonet.coalescent.BipartitionWeightCalculator.Quadrapartition;
import phylonet.coalescent.BipartitionWeightCalculator.Results;
import phylonet.dl.STBipartition;
import phylonet.tree.model.TNode;
import phylonet.tree.model.Tree;
import phylonet.tree.model.sti.STINode;
import phylonet.tree.model.sti.STITreeCluster;
import phylonet.tree.model.sti.STITreeCluster.Vertex;
import phylonet.util.BitSet;

public class WQInference extends AbstractInference<Tripartition> {

	protected int forceAlg = -1;
	protected long maxpossible;
	
	static TaxonIdentifier taxid = (TaxonIdentifier) GlobalMaps.taxonIdentifier;


	public WQInference(Options inOptions, List<Tree> trees, List<Tree> extraTrees, List<Tree> toRemoveExtraTrees) {
		super(inOptions, trees, extraTrees, toRemoveExtraTrees);

		this.forceAlg = inOptions.getAlg();
	}


	/**
	 * Calculates maximum possible score, to be used for normalization.
	 * @return
	 */
	protected long calculateMaxPossible() {
		if (weightCalculator instanceof WQWeightCalculator
				&& ((WQWeightCalculator)weightCalculator).algorithm instanceof WQWeightCalculator.CondensedTraversalWeightCalculator){
			return ((WQWeightCalculator.CondensedTraversalWeightCalculator)
					((WQWeightCalculator)weightCalculator).algorithm).polytree.maxScore / 4L
					- unresolvableQuartets();
		}

		//TODO: MUTIND: In the multi individual case, some quartets can never be satisfied. 
		//      We should compute their number and subtract that from maxpossible here. 
		long weight = 0;
		Integer  allsides = null;
		Iterator<STITreeCluster> tit = (this.dataCollection).treeAllClusters.iterator();
		boolean newTree = true;

		Deque<Integer> stack = new ArrayDeque<Integer>();
		// TODO: this should not use private stuff from weight calculator. 
		//       redo to use tree objects. 
		for (Integer gtb: getGeneTreesAsInt()){
			if (newTree) {
				allsides = tit.next().getBitSet().cardinality();
				newTree = false;
			}
			if (gtb >= 0){
				stack.push(1);
			} else if (gtb == Integer.MIN_VALUE) {
				stack.clear();
				newTree = true;
			}  else {
				ArrayList<Integer> children = new ArrayList<Integer>();
				Integer newSide = 0;
				for (int i = gtb; i < 0 ; i++) {
					Integer pop = stack.pop();
					children.add(pop);
					newSide+=pop;
				}
				stack.push(newSide);
				Integer sideRemaining = allsides - newSide;
				if ( sideRemaining !=0) {
					children.add(sideRemaining);
				}
				for (int i = 0; i < children.size(); i++) {
					Long a = children.get(i) + 0l;

					for (int j = i+1; j < children.size(); j++) {
						Long b = children.get(j) + 0l;
						/*if (children.size() > 5) {
                        	if ((side1.s0+side2.s0 == 0? 1 :0) +
                        			(side1.s1+side2.s1 == 0? 1 :0) + 
                        			(side1.s2+side2.s2 == 0? 1:0) > 1)
                        		continue;
                        }
						 */
						for (int k = j+1; k < children.size(); k++) {
							Long c = children.get(k) + 0l;
							weight += (a+b+c-3l) *a*b*c;
						}
					}
				}
			}
		}
        return weight/4l - unresolvableQuartets();
	}


	protected int[] getGeneTreesAsInt() {
		return ((WQWeightCalculator)this.weightCalculator).geneTreesAsInts();
	}


	private long unresolvableQuartets() {
		if (GlobalMaps.taxonNameMap.getSpeciesIdMapper().isSingleIndividual())
			return 0;
		long ret = 0;
		long four = 0;
		long three = 0;
		Iterator<Tree> ti = this.trees.iterator();
		System.err.print("Counting unresolvable quartets ... ");
		for (STITreeCluster gtCL : (this.dataCollection).treeAllClusters) {
			
			long[] counts = new long [GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSpeciesCount()]; // number of inds of each species
			long size = gtCL.getClusterSize();
			BitSet bs = gtCL.getBitSet();
	        for (int i = bs.nextSetBit(0); i >=0 ; i = bs.nextSetBit(i+1)) {
	            counts[(GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSpeciesIdForTaxon(i))]++;
	        }
	        
	        Tree t = ti.next();
	        
	        for (Long count: counts) {  
				
				// First compute how many quartets there are from single inds
	        	ret += (count*(count-1l)*(count-2l))/6l*(size-count)+        // 3 inds of this species + 1 other
	        			(count*(count-1l)*(count-2l)*(count-3l))/24l;    // 4 inds of this species
				
	        }
	        
	        Set<Integer> seenspecies = new HashSet<Integer>();
	        	
		    Stack<long []> stack =new Stack<long[]>();
			for (TNode n: t.postTraverse()) {
				if (n.isLeaf()) {
					int sp = GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSpeciesIdForTaxon(
		        			taxid.taxonId(n.getName()));
					if (counts[sp] >=3)
						seenspecies.add(sp);
	        		long[] p = new long[counts.length];
	        		p[sp] = p[sp]+1;
	        		stack.push(p);
	        		continue;
	        	}
    			Set<Integer> donespecies = new HashSet<Integer>();
    			long[]  sum1 = new long[counts.length], 
    					sum2 = new long[seenspecies.size()],
    					sum3 = new long[seenspecies.size()],
    					sum4 = new long[seenspecies.size()];
				
        		for (int i = 0; i < n.getChildCount(); i++) {
        			long[] pops = stack.pop();
        			int s = 0;
        			for (Integer species : seenspecies){
        				long pop = pops[species];
	        			if (pop != 0) {
		        			sum1[species] += pop;
		        			long popp = pop*pop;
		        			sum2[s] += popp;
							sum3[s] += popp*pop;
							sum4[s] += popp*popp;
	        			}

		        		if (sum1[species] == counts[species]) {
		        			donespecies.add(species);
		        		}
	        			s++;
        			}
        		}
        		stack.push(sum1);
        		
        		int sindex = 0;
    			for (Integer species : seenspecies){
		        	long s1 = sum1[species], s2 = sum2[sindex], s3 = sum3[sindex], s4 = sum4[sindex];
	        		if (s1 != 0 ) {
        				four += s1*(Math.pow(s1,3)  + 8*s3 - 6*s1*s2) - 6*s4 + 3*s2*s2;
    	        		three += (Math.pow(s1,3) + 2*s3 -3*s1*s2)*(size-counts[species]);
	        		}
	        		sindex++;
	        	}
		        
    			seenspecies.removeAll(donespecies);
	        }
	        
		}
		//Logging.log("four: "+four/24l);
		ret -= (three/6 + four/24l);
		Logging.log(ret+"");
		return ret;
	}
	
	@Override
	protected void initializeWeightCalculator() {
		this.weightCalculator.setupGeneTrees(this);
		if (this.forceAlg == 2) {
			((WQWeightCalculator)this.weightCalculator).useSetWeightsAlgorithm();
		} 

		this.weightCalculator.initializeWeightContainer(
				this.trees.size() *  taxid.taxonCount() * 2);
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
							sum += weightCalculator.getWeight(
									new Tripartition(childbslist.get(i),  childbslist.get(j), childbslist.get(k)), 
									null);
						}
					}					       
				}
			}
		}

		return logAndSet(st, sum, poly);

	}


	protected void initForScoring() {
		mapNames();

		IClusterCollection clusters = newClusterCollection();


		this.dataCollection = Factory.instance.newCounter(clusters, this);
		weightCalculator = newWeightCalculatorForWeigth();

		WQDataCollection wqDataCollection = (WQDataCollection) this.dataCollection;
		wqDataCollection.preProcess(this);
		this.initializeWeightCalculator();			
		//ASTRAL IV SPECIFIC
		
		this.maxpossible = this.calculateMaxPossible();
		Logging.log("Number of quartet trees in the gene trees: "+this.maxpossible);

		//Logging.log(this.maxpossible);
	}


	protected double logAndSet(Tree st, long sum, boolean poly) {
		Logging.logTimeMessage("WQInference 180: ");

		if (poly) {
			Logging.log("Final quartet score is: won't report because of the existense of polytomies and to save time. "
					+ "To get the score run with -t 1 and you can score the tree below using -q. ");
			Logging.log("Final normalized quartet score is: won't report because of the existense of polytomies and to save time. "
					+ "To get the score run with -t 1 and you can score the tree below using -q. ");
		} else {
			
			Logging.log("Final quartet score is: " + sum/4l);
			Logging.log("Final normalized quartet score is: "+ (sum/4l+0.)/this.maxpossible);
			//System.out.println(st.toNewickWD());
		}

		if (this.getBranchAnnotation() == 0){
			for (TNode n: st.postTraverse()) {
				((STINode) n).setData(null);
			}
		} else {
			double logscore = this.scoreBranches(st);

			if (this.getBranchAnnotation() % 12 == 0) {
				Logging.log("log local posterior: "+logscore);
				return logscore;
			}
		}
		return (sum/4l+0.)/this.maxpossible;
	}


	protected ArrayList<STITreeCluster> handleInternalNode(Stack<STITreeCluster> stack, TNode node) {
		ArrayList<STITreeCluster> childbslist = new ArrayList<STITreeCluster>();
		BitSet bs = new BitSet(taxid.taxonCount());
		for (TNode child: node.getChildren()) {
			STITreeCluster pop = stack.pop();
			childbslist.add(pop);
			bs.or(pop.getBitSet());
		}

		STITreeCluster cluster = Factory.instance.newCluster(taxid);
		cluster.setCluster((BitSet) bs.clone());

		//((STINode)node).setData(new GeneTreeBitset(node.isRoot()? -2: -1));
		stack.add(cluster);


		STITreeCluster remaining = cluster.complementaryCluster();
		if (remaining.getClusterSize() != 0) {
			childbslist.add(remaining);
		}
		
		if (childbslist.size() > 3) {
			/*for (STITreeCluster chid :childbslist) {
				System.err.print(chid.getClusterSize()+" ");
			}
			Logging.log(" (polytomy)");*/
			return null;
		}
		return childbslist;
	}


	protected void handleLeaf(Stack<STITreeCluster> stack, TNode node) {
		String nodeName = node.getName(); //GlobalMaps.TaxonNameMap.getSpeciesName(node.getName());

		STITreeCluster cluster = Factory.instance.newCluster(taxid);
		Integer taxonID = taxid.taxonId(nodeName);
		cluster.addLeaf(taxonID);

		stack.add(cluster);
	}


	protected boolean skipNode (TNode node) {
		TNode parent = node.getParent();
		
		return 	node.isLeaf() || node.isRoot() || node.getChildCount() > 2 || 
				(parent.getChildCount() > 3) ||
				(parent.getChildCount() > 2 && !parent.isRoot()) ||
				((parent.isRoot() && parent.getChildCount() == 2 && node.getSiblings().get(0).getChildCount() != 2));
	}

	protected class NodeData {
		public NodeData() {}
		public Double mainfreq;
		public Double alt1freqs;
		public Double alt2freqs;
		public Long quartcount;
		public Integer effn ;
		public Quadrapartition [] quads;
		public STBipartition[] bipartitions;

	}

	/**
	 * Annotates the species tree branches with support, branch length, etc. 
	 * @param st
	 * @return
	 */
	protected double scoreBranches(Tree st) {
		Logging.logTimeMessage("WQInference 227: " );

		//weightCalculator = new BipartitionWeightCalculator(this,((WQWeightCalculator)this.weightCalculator).geneTreesAsInts());

		BipartitionWeightCalculator weightCalculator2 = new BipartitionWeightCalculator(this,getGeneTreesAsInt());
		WQDataCollection wqDataCollection = (WQDataCollection) this.dataCollection;
		//wqDataCollection.initializeWeightCalculator(this);


		updateNodeData(st);

		Stack<STITreeCluster> stack = new Stack<STITreeCluster>();
		
		Logging.logTimeMessage("WQInference 274: " );
		
		/**
		 * For each node,
		 *   1. create three quadripartitoins for the edge above it
		 *   2. score the quadripartition
		 *   3. save the scores in a list for annotations in the next loop
		 */
		NodeData [] nodeDataList = new NodeData [st.getLeafCount()*2];
		int ni = 0;
		for (TNode n: st.postTraverse()) {
			STINode node = (STINode) n;
			if (node.isLeaf()) {
				stack.push((STITreeCluster) node.getData());
			} else {

				NodeData nd = null;

				STITreeCluster cluster = (STITreeCluster) node.getData();		
				STITreeCluster c1 = null, c2 = null;
				long cs = cluster.getClusterSize()+0l;
				
				for (int i =0; i< node.getChildCount(); i++) {
					if (c1 == null)
						c1 = stack.pop();
					else if (c2 == null)
						c2 = stack.pop();
					else
						stack.pop();
				}
				stack.push(cluster);

				scoreBranches(weightCalculator2, nodeDataList, ni, node, nd, cluster, c1, c2, cs);
				ni++;
			}
		}

		return annotateBranch(st, nodeDataList, ni);
	}


	/**
	 * Annotate each branch by updating its data field
	 * according to scores and user's annotation preferences. 
	 */
	protected double annotateBranch(Tree st, NodeData[] nodeDataList, int ni) {

		/**
		 * Add bitsets to each node for all taxa under it. 
		 * Bitsets are saved in nodes "data" field
		 */
		BufferedWriter freqWriter = null;
		BufferedWriter Rscript = null;
		//List<String> freqWriterLines = new ArrayList<String>();
		if (this.getBranchAnnotation() % 16 == 0) {
			String freqOutputPath = this.options.getFreqOutputPath();
			try {
				Rscript = new BufferedWriter(new FileWriter(freqOutputPath + File.separator+ "freqQuadVisualization.R"));
				freqWriter = new BufferedWriter(new FileWriter(freqOutputPath + File.separator+ "freqQuad.csv"));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		
		double ret = 0;
		NodeData nd = null;
		int nj = 0;
		for (TNode n: st.postTraverse()) {
			STINode node = (STINode) n;

			if (node.isLeaf()) {
				node.setData(null);
				continue;
			} 
			
			nd = nodeDataList[nj]; nj++;
			if (nd == null ) {
				node.setData(null);
				continue;
			} 
			Double f1 = nd.mainfreq;
			Double f2 = nd.alt1freqs;
			Double f3 = nd.alt2freqs;
			Long quarc = nd.quartcount;
			Double effni = nd.effn + 0.0;
			

			if ( Math.abs((f1+f2+f3) - effni) > 0.0001 ) {
				//Logging.log("Adjusting effective N from\t" + effni + "\tto\t" + (f1 + f2 + f3) + ". This should only happen as a result of polytomies in gene trees.");
				effni = f1 + f2 + f3;
			}

			if (effni == 0) {
				Logging.log("Warning: node has effective N ==0 and so no quartet resolutions: \n" + n);
			}

			if (this.options.getGeneRepeat() != 1) {
				f1 /= this.options.getGeneRepeat();
				f2 /= this.options.getGeneRepeat();
				f3 /= this.options.getGeneRepeat();
				effni /= this.options.getGeneRepeat();
			}
			//Long sum = p+a1+a2;

			Posterior post = new Posterior(
					f1,f2,f3,(double)effni, options.getLambda());
			double bl = post.branchLength();

			node.setParentDistance(bl);
			if (this.getBranchAnnotation() == 0){
				node.setData(null);
			} else if (this.getBranchAnnotation() == 1){
				if (effni != 0)
					node.setData(df.format((f1+.0)/effni*100));
				else 
					node.setData(null);
			} else if (this.getBranchAnnotation() == 10) {
				df.setMaximumFractionDigits(5);
				double pval = post.getPvalue();
				if (pval < 0) {
					Logging.log(""
							+ "Cannot perform polytomy test with effective N (after polytomies) "+ effni +
							":\n\t" +
							node);
					node.setData("NA");
				} else {
					node.setData(df.format(pval));
				}
			} else {
				double postQ1 = post.getPost();
				ret += Math.log(postQ1);
				

				if (this.getBranchAnnotation() == 3 || this.getBranchAnnotation() == 12) {
					node.setData(df.format(postQ1));
				} else if (this.getBranchAnnotation() % 2 == 0) {

					post = new Posterior(f2,f1,f3,(double)effni, options.getLambda());
					double postQ2 = post.getPost();
					post =  new Posterior(f3,f1,f2,(double)effni, options.getLambda());
					double postQ3 = post.getPost();

					if (this.getBranchAnnotation() == 2)
						node.setData(
								"'[q1="+(f1)/effni+";q2="+(f2)/effni+";q3="+(f3)/effni+
								";f1="+f1+";f2="+f2+";f3="+f3+
								";pp1="+postQ1+";pp2="+postQ2+";pp3="+postQ3+
								";QC="+quarc+";EN="+effni+"]'");
					else if (this.getBranchAnnotation() == 4) {
						node.setData("'[pp1="+df.format(postQ1)+";pp2="+df.format(postQ2)+";pp3="+df.format(postQ3)+"]'");
					} else if (this.getBranchAnnotation() == 6){
						node.setData(df.format(postQ1));
						Quadrapartition[] threequads = nd.quads;
						STBipartition[] biparts = nd.bipartitions;
						Logging.log(threequads[0] +
								" [" + biparts[0].toString2() +"] : "+postQ1 +" ** f1 = "+f1+
								" f2 = "+f2+" f3 = "+f3+" EN = "+ effni+" **");
						Logging.log(threequads[1] +
								" ["+biparts[1].toString2()+"] : "+postQ2+ " ** f1 = "+f2+
								" f2 = "+f1+" f3 = "+f3+" EN = "+ effni+" **");
						Logging.log(threequads[2] +
								" ["+biparts[2].toString2()+"] : "+postQ3+ " ** f1 = "+f3+
								" f2 = "+f1+" f3 = "+f2+" EN = "+ effni+" **");
					}  else if (this.getBranchAnnotation() == 8){
						node.setData(
								"'[q1="+df.format((f1)/effni)+
								";q2="+df.format((f2)/effni)+
								";q3="+df.format((f3)/effni)+"]'");
					} else if (this.getBranchAnnotation() % 16 == 0) {
						node.setData("'[pp1="+df.format(postQ1)+";pp2="+df.format(postQ2)+";pp3="+df.format(postQ3)+"]'");	
						Quadrapartition[] threequads = nd.quads;
						//STBipartition[] biparts = nd.bipartitions;

						if (threequads == null)
							continue;
						
						try {
							if (this.getBranchAnnotation() == 16) {
								String lineTmp = node.getName() + "\t" + "t1" + "\t" + threequads[0].toString2() + "\t" + 
									Double.toString(postQ1) + "\t" + Double.toString(f1) +
									"\t" + Double.toString(effni);
								freqWriter.write(lineTmp + "\n");
	
								lineTmp = node.getName() + "\t" + "t2" + "\t" + threequads[1].toString2() + "\t" + 
										Double.toString(postQ2) + "\t" + Double.toString(f2) + 
										"\t" + Double.toString(effni);
								freqWriter.write(lineTmp + "\n");
	
								lineTmp = node.getName() + "\t" + "t3" + "\t" + threequads[2].toString2() + "\t" +
										Double.toString(postQ3) + "\t" + Double.toString(f3) +
										"\t" + Double.toString(effni);
								freqWriter.write(lineTmp + "\n"); } 
							else {
								String lineTmp = node.getName() + "\t" + "t1" + "\t" + threequads[0].toString2() + "\t" + 
										Double.toString((f1)/effni) + "\t" + Double.toString(f1) +
										"\t" + Double.toString(effni);
								freqWriter.write(lineTmp + "\n");
	
								lineTmp = node.getName() + "\t" + "t2" + "\t" + threequads[1].toString2() + "\t" + 
										Double.toString((f2)/effni) + "\t" + Double.toString(f2) + 
										"\t" + Double.toString(effni);
								freqWriter.write(lineTmp + "\n");
	
								lineTmp = node.getName() + "\t" + "t3" + "\t" + threequads[2].toString2() + "\t" +
										Double.toString((f3)/effni) + "\t" + Double.toString(f3) +
										"\t" + Double.toString(effni);
								freqWriter.write(lineTmp + "\n"); 
								}
						} catch (IOException e) {
							throw new RuntimeException(e);
						}

					}
				}
				//i++;
			} 
		}
		Logging.logTimeMessage("WQInference 478: ");
		
		if (! (ni == nj))
			throw new RuntimeException("Hmm, this shouldn't happen; "+nodeDataList);
		if (this.getBranchAnnotation() % 16 == 0) {
			try {
				Rscript.write("#!/usr/bin/env Rscript\n");
				Rscript.write("red='#d53e4f';orange='#1d91c0';blue='#41b6c4';colormap = c(red,orange,blue)\n");
				Rscript.write("require(reshape2);require(ggplot2);\n");
				Rscript.write("dirPath = '.'; filePath = paste(dirPath"
						+ ",'/freqQuadCorrected.csv',sep=''); md<-read.csv(filePath,header=F,sep='\\t'); md$value = md$V5/md$V6;\n");
				Rscript.write("a<-length(levels(as.factor(md$V7)))*3.7; b<-4; sizes <- c(a,b);\n");
				Rscript.write("md$V8<-reorder(md$V8,-md$value)\n");
				Rscript.write("ggplot(data=md)+aes(x=V8,y=value,fill=V9)+"
						+ "geom_bar(stat='identity',color=1,width=0.8,position='dodge')+"
						+ "theme_bw()+theme(axis.text.x=element_text(angle=90))+scale_fill_manual"
						+ "(values=colormap,name='Topology')+geom_hline(yintercept=1/3,size=0.4,linetype=2)+"
						+ "ylab('relative freq.')+facet_wrap(~V7,scales='free_x')+xlab('')\n");
				Rscript.write("pdfFile = paste(dirPath,'/relativeFreq.pdf',sep=''); ggsave(pdfFile,width = sizes[1], height= sizes[2]);\n");
				Rscript.close();
				freqWriter.close();
			} catch (IOException e) {
				throw new RuntimeException("Hmm, the Rscript and frequency of Quadripartition files cannot be created!");
			}

		}
		Logging.log("Extended species tree:");
		Logging.log(st.toStringWD());
		return ret;
	}


	/**
	 * Add bitsets to each node for all taxa under it. 
	 * Bitsets are saved in nodes "data" field
	 */
	protected void updateNodeData(Tree st) {
		
		Stack<STITreeCluster> stack = new Stack<STITreeCluster>();
		
		int numNodes = 0;
		for (TNode n: st.postTraverse()) {
			STINode node = (STINode) n;
			if (node.isLeaf()) {
				String nodeName = node.getName(); //GlobalMaps.TaxonNameMap.getSpeciesName(node.getName());

				STITreeCluster cluster =  Factory.instance.newCluster(taxid);
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

				STITreeCluster cluster =  Factory.instance.newCluster(taxid);
				cluster.setCluster((BitSet) bs.clone());

				//((STINode)node).setData(new GeneTreeBitset(node.isRoot()? -2: -1));
				stack.add(cluster);
				node.setData(cluster);
				if (options.getBranchannotation() % 16 == 0) {
					String ndName = "N" + Integer.toString(numNodes);
					numNodes += 1;
					node.setName(ndName);
				}
			}
		}
	}


	public void scoreBranches(BipartitionWeightCalculator weightCalculator2, NodeData[] nodeDataList, int i,  STINode node,
			NodeData nd, STITreeCluster cluster, STITreeCluster c1, STITreeCluster c2, long cs) {
		scoreBranchesLoop loop = new scoreBranchesLoop(weightCalculator2, nodeDataList, i, node, nd, cluster, c1, c2, cs);
		loop.compute();
	}

	public class scoreBranchesLoop {
		BipartitionWeightCalculator weightCalculator2;
		NodeData [] nodeDataList;
		STINode node;
		NodeData nd;
		STITreeCluster cluster;
		STITreeCluster c1;
		STITreeCluster c2;
		long cs;
		int i; 
		public scoreBranchesLoop(BipartitionWeightCalculator weightCalculator2, NodeData []  nodeDataList, int i, STINode node,
				NodeData nd, STITreeCluster cluster, STITreeCluster c1, STITreeCluster c2, long cs) {
			this.weightCalculator2 = weightCalculator2;
			this.nodeDataList = nodeDataList;
			this.node = node;
			this.nd = nd;
			this.cluster = cluster;
			this.c1 = c1;
			this.c2 = c2;
			this.cs = cs;
			this.i = i;
		}
		public void compute() {
			/**
			 * For terminal branches in a multi-ind data
			 */
			if (cs > 1 && GlobalMaps.taxonNameMap.getSpeciesIdMapper().isSingleSP(cluster.getBitSet()))
			{
				STITreeCluster[] sisterRemaining = getSisterRemaining(node);
				STITreeCluster sister = sisterRemaining[0]; 
				STITreeCluster remaining = sisterRemaining[1];				
				nd = getNodeData(0d, 0d, 0d, 0);
				nodeDataList[i] = nd;
				
				/**
				 * Compute a quadripartition per each individual
				 */
				BitSet bitSet = cluster.getBitSet();			
				for (int j = bitSet.nextSetBit(0); j >= 0; j = bitSet.nextSetBit(j + 1)) {
					c1 = new STITreeCluster(cluster);
					c1.getBitSet().clear(j);
					c2 = Factory.instance.newCluster(taxid);
					c2.getBitSet().set(j);
					Quadrapartition[] threequads = new Quadrapartition [] { 
							weightCalculator2.new Quadrapartition (c1,  c2, sister, remaining), 
							weightCalculator2.new Quadrapartition (c1, sister, c2, remaining),
							weightCalculator2.new Quadrapartition (c1, remaining, c2, sister)
					};
					
					/**
					 * Scores all three quadripartitoins
					 */
					Results s = weightCalculator2.getWeight(threequads);
	
					nd.mainfreq += s.getQs()[0];
					nd.alt1freqs += s.getQs()[1];
					nd.alt2freqs += s.getQs()[2];
					nd.effn += s.getEffn();
				}
				
				/**
				 * Average frequencies. TODO: Good with missing data?
				 */
				nd.mainfreq /= cs;
				nd.alt1freqs /= cs;
				nd.alt2freqs /= cs;
				nd.effn /= (int) cs;
				
				nd.quartcount =  (cs*(cs-1)/2)
						* (sister.getClusterSize()+0l)
						* (remaining.getClusterSize()+0l);
									
			} else if (! skipNode(node) ) { 
				/**
				 * Normal internal branches
				 */
				STITreeCluster[] sisterRemaining = getSisterRemaining(node);
				STITreeCluster sister = sisterRemaining[0]; 
				STITreeCluster remaining = sisterRemaining[1];
				
				Quadrapartition[] threequads = new Quadrapartition [] { 
						weightCalculator2.new Quadrapartition (c1,  c2, sister, remaining), 
						weightCalculator2.new Quadrapartition (c1, sister, c2, remaining),
						weightCalculator2.new Quadrapartition (c1, remaining, c2, sister)
				};
				/**
				 * 2. Scores all three quadripartitoins
				 */
				Results s = weightCalculator2.getWeight(threequads);
				nd = getNodeData(s.getQs()[0],s.getQs()[1],s.getQs()[2],s.getEffn());
				nodeDataList[i] = nd ;
	
				nd.quartcount= (c1.getClusterSize()+0l)
						* (c2.getClusterSize()+0l)
						* (sister.getClusterSize()+0l)
						* (remaining.getClusterSize()+0l);
	
	
				if (getBranchAnnotation() == 7){
					if (remaining.getClusterSize() != 0 && sister.getClusterSize() != 0 && c2.getClusterSize() != 0 && c1.getClusterSize() != 0 ){
						System.err.print(c1.toString()+c2.toString()+"|"+sister.toString()+remaining.toString()+"\n");
					}
				}
				if (getBranchAnnotation() == 6 || getBranchAnnotation() % 16 == 0) {
					STITreeCluster c1plussis = Factory.instance.newCluster(taxid);
	
					c1plussis.setCluster((BitSet) c1.getBitSet().clone());
					c1plussis.getBitSet().or(sister.getBitSet());
					STITreeCluster c1plusrem = Factory.instance.newCluster(taxid);
					c1plusrem.setCluster((BitSet) c1.getBitSet().clone());
					c1plusrem.getBitSet().or(remaining.getBitSet());
	
					STBipartition bmain = new STBipartition(cluster, cluster.complementaryCluster());
					STBipartition b2 = new STBipartition(c1plussis, c1plussis.complementaryCluster());
					STBipartition b3 = new STBipartition(c1plusrem, c1plusrem.complementaryCluster());
	
					STBipartition[] biparts = new STBipartition[] {bmain, b2, b3};
					nd.quads = threequads;
					nd.bipartitions = biparts;
				}
			} else {
				/**
				 * Root or trivial branches
				 */
				nodeDataList[i] = null;
			}
			
			if (nd != null && nd.effn < 20) {
				Logging.log("You may want to ignore posterior probabilities and other statistics related to the following "
						+ "branch branch because the effective number of genes impacting it is only "+ nd.effn +
						":\n\t" +
						GlobalMaps.taxonNameMap.getSpeciesIdMapper().getSTClusterForGeneCluster(cluster));
			}
		}
	}


	protected NodeData getNodeData(Double m, Double a1, Double a2, Integer en) {
		NodeData nd;
		nd = new NodeData();
		nd.mainfreq = m;
		nd.alt1freqs=a1;
		nd.alt2freqs=a2;
		nd.effn = en;
		return nd;
	}


	protected STITreeCluster[] getSisterRemaining(STINode node) {
		STITreeCluster [] sisterRemaining = {null,null};
		Iterator<STINode> siblingsIt = node.getParent().getChildren().iterator();
		STINode sibling = siblingsIt.next();
		
		if ( sibling == node ) sibling = siblingsIt.next(); 
		
		sisterRemaining[0] = (STITreeCluster)sibling.getData();
		
		if (node.getParent().isRoot() && node.getParent().getChildCount() == 3) {
			sibling = siblingsIt.next();
			if (sibling == node) sibling = siblingsIt.next(); 
			sisterRemaining[1] = (STITreeCluster)sibling.getData();;					
		}  else if (node.getParent().isRoot() && node.getParent().getChildCount() == 2) {
			if (sibling.getChildCount() == 2) {
				Iterator<STINode> nieceIt = sibling.getChildren().iterator();
				sisterRemaining[0] = (STITreeCluster) nieceIt.next().getData();
				sisterRemaining[1] = (STITreeCluster) nieceIt.next().getData();
			} else {
				Logging.log("WARN: we should never be here; something wrong with branch annotations (but topology will be fine). ");
			}
		} 
		else {
			sisterRemaining[1] = ((STITreeCluster)node.getParent().getData()).complementaryCluster();
		}
		return sisterRemaining;
	}


	@Override
	public Long getTotalCost(Vertex all) {
		Logging.logTimeMessage("WQInference 475: ");
			
		Logging.log("Normalized score (portion of input quartet trees satisfied before correcting for multiple individuals): " + 
				all._max_score/4./this.maxpossible);
		return (long) (all._max_score/4l);
	}


	@Override
	public AbstractComputeMinCostTask<Tripartition> newComputeMinCostTask(AbstractInference<Tripartition> dlInference,
			Vertex all, IClusterCollection clusters) {
		return new WQComputeMinCostTask( (WQInference) dlInference, all,  clusters);
	}

	public IClusterCollection newClusterCollection() {
		return new WQClusterCollection(GlobalMaps.taxonIdentifier.taxonCount());
	}

	public  WQDataCollection newCounter(IClusterCollection clusters) {
		return (WQDataCollection) Factory.instance.newCounter(clusters, this);
	}



	@Override
	public  AbstractWeightCalculator<Tripartition> newWeightCalculator() {
		return new WQWeightCalculator(this);
	}
	
	public AbstractWeightCalculator<Tripartition> newWeightCalculatorForWeigth() {
		AbstractWeightCalculator wc =  newWeightCalculator();
		return wc;
	}


	@Override
	public  void setupMisc() {
		this.maxpossible = this.calculateMaxPossible();
		Logging.log("Number of quartet trees in the gene trees: " +
				this.maxpossible);

	}

	/**
	 * obsolete
	 */
	private void automaticallyDecideAlgorithm(int geneTreeTripartitonCountSize, int k){
		if (this.forceAlg != -1) {
			return;
		}
		if (k <= 0 || geneTreeTripartitonCountSize <= 0) {
			throw new RuntimeException("gene tree tripartition size or k not set properly");
		}
		if (this.forceAlg == -1) {
			this.forceAlg = ( GlobalMaps.taxonIdentifier.taxonCount() <= 32 || (geneTreeTripartitonCountSize < k*6)) ? 2 : 1;
		} else {
			throw new RuntimeException("Algorithm already set");
		}
	}
}
