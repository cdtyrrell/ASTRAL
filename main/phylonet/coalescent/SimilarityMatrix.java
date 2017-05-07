package phylonet.coalescent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;


import java.util.TreeSet;

import phylonet.tree.model.TNode;
import phylonet.tree.model.Tree;
import phylonet.tree.model.sti.STITreeCluster;
import phylonet.util.BitSet;

/**
 * Implements a Distance method
 * @author smirarab
 *
 */
public class SimilarityMatrix {
	
	private Float[][] similarityMatrix;
	private List<TreeSet<Integer>> orderedTaxonBySimilarity;
	private Integer n;
	
	public SimilarityMatrix(int n) {
		this.n = n;
	}
	
	int getBetterSideByFourPoint(int x, int a, int b, int c) {
		double xa = this.similarityMatrix[x][a];
		double xb = this.similarityMatrix[x][b];
		double xc = this.similarityMatrix[x][c];
		double ab = this.similarityMatrix[a][b];
		double ac = this.similarityMatrix[a][c];
		double bc = this.similarityMatrix[b][c];
		double ascore = xa + bc  - (xb + ac); // Note this is similartiy, not distance
		double bscore = xb + ac  - (xa + bc); 
		double cscore = xc + ab - (xb + ac); 
		return ascore >= bscore ?
				ascore >= cscore ? a : c :
					bscore >= cscore ? b : c;	
	}
	
	private List<TreeSet<Integer>> sortByDistance(Float[][] refMatrix) {
		List<TreeSet<Integer>> ret = new ArrayList<TreeSet<Integer>>(n);
		List<Integer> range = Utils.getRange(n);
		for (int i = 0; i < n; i++) {
			final Float[] js = refMatrix[i];
			TreeSet<Integer> indices = sortColumn(range, js);
			ret.add(indices);
		}
		return ret;
	}

	private TreeSet<Integer> sortColumn(List<Integer> range, final Float[] js) {
		TreeSet<Integer> indices = new TreeSet<Integer>(new Comparator<Integer>() {

			@Override
			public int compare(Integer o1, Integer o2) {
				if (o1 == o2) {
					return 0;
				}
				int comp = Float.compare(js[o1], js[o2]) ;
				return  comp == 0 ? - o1.compareTo(o2) : - comp;
			}
		});
		indices.addAll(range);
		return indices;
	}
	
	private void assureOrderedTaxa () {
		if (this.orderedTaxonBySimilarity == null) {
			this.orderedTaxonBySimilarity = this.sortByDistance(this.similarityMatrix);
		}
	}

	/**
	 * Returns the id of the closest taxon that is either present in presentBS or
	 * has a smaller id than mssingId
	 * @param presentBS
	 * @param missingId
	 * @return
	 */
	int getClosestPresentTaxonId(BitSet presentBS, int missingId) {
		this.assureOrderedTaxa();
		int closestId = -1;
		for (Integer other: this.orderedTaxonBySimilarity.get(missingId)){
			if ( missingId > other // other is already added
					|| presentBS.get(other) // other was in original tree
					) {
				closestId = other;
				break;
			}
		}
		if (closestId == -1) {
			throw new RuntimeException("Bug: this should not be reached");
		}
		return closestId;
	}

	
	private void updateQuartetDistanceForPair (Integer treeall, BitSet left,
			BitSet right, Float[][] matrix) {
		long c = treeall - left.cardinality() - right.cardinality();
		c = c*(c-1)/2;
		for (int l = left.nextSetBit(0); l >= 0; l=left.nextSetBit(l+1)) {
			for (int r = right.nextSetBit(0); r >= 0; r=right.nextSetBit(r+1)) {
				if(r > l) {
					synchronized(matrix[l][r]){
						matrix[l][r] += c;
						matrix[r][l] = matrix[l][r];
					}
				}
				else {
					synchronized(matrix[r][l]){
						matrix[l][r] += c;
						matrix[r][l] = matrix[l][r];
					}
				}
				
			}
		}
	}
	
	void populateByQuartetDistance(List<STITreeCluster> treeAllClusters, List<Tree> geneTrees) {
		this.similarityMatrix = new Float[n][n];
		Long [][] denom = new Long [n][n];
		fillZero2D(this.similarityMatrix);
		for(int i = 0; i < n; i++) {
			for(int j = 0; j < n; j++) {
				denom[i][j] = 0L;
			}
		}
		if(CommandLine.timerOn) {
			System.err.println("TIME TOOK FROM LAST NOTICE SimilarityMatrix 145-148: " + (double)(System.nanoTime()-CommandLine.timer)/1000000000);
			CommandLine.timer = System.nanoTime();
			System.out.println(n);
		}
		int k = 0;
		CountDownLatch latch = new CountDownLatch(geneTrees.size());

		for (Tree tree :  geneTrees) {
			CommandLine.eService.execute(new populateByQuartetDistanceLoop(treeAllClusters.get(k++), tree, denom, latch));
		}
		try {
			latch.await();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if(CommandLine.timerOn) {
			System.err.println("TIME TOOK FROM LAST NOTICE SimilarityMatrix 161-164: " + (double)(System.nanoTime()-CommandLine.timer)/1000000000);
			CommandLine.timer = System.nanoTime();
		}
		for (int i = 0; i < n; i++) {
			for (int j = i; j < n; j++) {
				if (denom[i][j] == 0)
					similarityMatrix[i][j] = 0F;
				else
					similarityMatrix[i][j] = similarityMatrix[i][j] / (denom[i][j]/2);
				if (i == j) {
					similarityMatrix[i][j] = 1F;
				}
				similarityMatrix[j][i] = similarityMatrix[i][j];
			}
		}
	}
	public class populateByQuartetDistanceLoop implements Runnable{
		STITreeCluster treeallCL;
		Tree tree;
		Long[][] denom;
		CountDownLatch latch;

		public populateByQuartetDistanceLoop(STITreeCluster treeallCL, Tree tree, Long[][] denom, CountDownLatch latch) {
			this.treeallCL = treeallCL;
			this.tree = tree;
			this.denom = denom;
			this.latch = latch;
		}
		public void run() {
			Deque<BitSet> stack = new ArrayDeque<BitSet>();

			Integer treeall = treeallCL.getClusterSize();
			
			for (TNode node : tree.postTraverse()) {
				if (node.isLeaf()) {
					BitSet tmp = new BitSet(GlobalMaps.taxonIdentifier.taxonCount());
					tmp.set(GlobalMaps.taxonIdentifier.taxonId(node.getName()));
					stack.push(tmp);
				} else if (node.isRoot() && node.getChildCount() == 3){
					BitSet left = stack.pop();
					BitSet middle = stack.pop();
					BitSet right = stack.pop();
					updateQuartetDistanceForPair(treeall, left, right, similarityMatrix);
					updateQuartetDistanceForPair(treeall, left, middle, similarityMatrix);
					updateQuartetDistanceForPair(treeall, middle, right, similarityMatrix);
				} else {
					BitSet left = stack.pop();
					BitSet right = stack.pop();
					BitSet both = new BitSet();
					both.or(left);
					both.or(right);
					BitSet middle = new BitSet();
					middle.or(treeallCL.getBitSet());
					middle.andNot(both); 
					updateQuartetDistanceForPair(treeall, left, right, similarityMatrix);
					updateQuartetDistanceForPair(treeall, left, middle, similarityMatrix);
					updateQuartetDistanceForPair(treeall, middle, right, similarityMatrix);
					stack.push(both);
				}
			}

			BitSet all = treeallCL.getBitSet();
			int c = all.cardinality() - 2;
			for (int l = all.nextSetBit(0); l >= 0; l=all.nextSetBit(l+1)) {
				for (int r = all.nextSetBit(0); r >= 0; r=all.nextSetBit(r+1)) {
					if(r > l) {
						synchronized(denom[l][r]){
							denom[l][r] += c*(c-1)/2;
							denom[r][l] = denom[l][r];
						}
					}
					else {
						synchronized(denom[r][l]){
							denom[l][r] += c*(c-1)/2;
							denom[r][l] = denom[l][r];
						}
					}
					
				}
			}
			latch.countDown();

		}
	}
	SimilarityMatrix convertToSpeciesDistance(SpeciesMapper spm) {
		Float [][] STsimMatrix = new Float[spm.getSpeciesCount()][spm.getSpeciesCount()];
		Float[][] denum = new Float[spm.getSpeciesCount()][spm.getSpeciesCount()];
		fillZero2D(STsimMatrix);
		fillZero2D(denum);

		for (int i = 0; i < n; i++) {
			for (int j = i; j < n; j++) {
				int stI =  spm.getSpeciesIdForTaxon(i);
				int stJ =  spm.getSpeciesIdForTaxon(j);
				STsimMatrix[stI][stJ] += this.similarityMatrix[i][j]; 
				STsimMatrix[stJ][stI] = STsimMatrix[stI][stJ];
				denum[stI][stJ] ++;
				denum[stJ][stI] ++;
			}
		}
		for (int i = 0; i < spm.getSpeciesCount(); i++) {
			for (int j = 0; j < spm.getSpeciesCount(); j++) {
				STsimMatrix[i][j] = denum[i][j] == 0 ? 0 : 
					STsimMatrix[i][j] / denum[i][j];
			}
			STsimMatrix[i][i] = 1F;
			//System.err.println(Arrays.toString(this.distSTMatrix[i]));
		}
		//johng23
		if(CommandLine.timerOn) {
			System.err.println("TIME TOOK FROM LAST NOTICE: " + (double)(System.nanoTime()-CommandLine.timer)/1000000000);
			CommandLine.timer = System.nanoTime();
		}
		System.err.println("Species tree distances calculated ...");
		
		SimilarityMatrix ret = new SimilarityMatrix(STsimMatrix.length);
		ret.similarityMatrix = STsimMatrix;
		
		return ret;
	}
	
	SimilarityMatrix getInducedMatrix(HashMap<String, Integer> randomSample) {
		
		int sampleSize = randomSample.size();
		Float[][] sampleSimMatrix = new Float [sampleSize][sampleSize];
		fillZero2D(sampleSimMatrix);

		for (Entry<String, Integer> row : randomSample.entrySet()) {
			int rowI = GlobalMaps.taxonIdentifier.taxonId(row.getKey());
			int i = row.getValue();
			for (Entry<String, Integer> col : randomSample.entrySet()) {
				int colJ = GlobalMaps.taxonIdentifier.taxonId(col.getKey());
				sampleSimMatrix[i][col.getValue()] = this.similarityMatrix[rowI][colJ];
			}
		}
		SimilarityMatrix ret = new SimilarityMatrix(sampleSize);
		ret.similarityMatrix = sampleSimMatrix;
		return ret;
	}

	//TODO: generate iterable, not list
	Iterable<BitSet> getQuadraticBitsets() {
		List<BitSet> newBitSets = new ArrayList<BitSet>();
		ArrayList<Integer> inds = new ArrayList<Integer> (n);
		for (int i = 0; i < n; i++) {
			inds.add(i);
		}
		for (final Float[] fs : this.similarityMatrix) {
			Collections.sort(inds, new Comparator<Integer>() {

				@Override
				public int compare(Integer i1, Integer i2) {
					if (i1 == i2) {
						return 0;
					}
					int vc = Float.compare(fs[i1],fs[i2]);
					if (vc != 0) {
						return - vc;
					}
					return i1 > i2 ? 1 : -1;
				}
			});
			BitSet stBS = new BitSet(n);
			//Float previous = fs[inds.get(1)];
			//Float lastStep = 0;
			for (int sp : inds) {
				stBS.set(sp);
				/*if (previous - fs[sp] < 0) {
						continue;
					}*/
				newBitSets.add((BitSet) stBS.clone());
				//lastStep = previous - fs[sp];
				//previous = fs[sp];
			}
			//System.err.println(this.clusters.getClusterCount());
		}
		return newBitSets;
	}
	
	
	List<BitSet> resolveByUPGMA(List<BitSet> bsList, boolean original) {
		
		List<BitSet> internalBSList;
		if (original) {
			internalBSList = new ArrayList<BitSet>(bsList);
		} else {
			internalBSList = new ArrayList<BitSet>();
		}
		
		int size = bsList .size();
		List<TreeSet<Integer>> indsBySim = new ArrayList<TreeSet<Integer>>(size);
		List<Float[]> sims = new ArrayList<Float[]>(size);
		List<Integer> range = Utils.getRange(size);
		List<Integer> weights = new ArrayList<Integer>(size);
		
		for (int i = 0; i < size; i++) {
			if (!original) {
				BitSet internalBS = new BitSet(size);
				internalBS.set(i);
				internalBSList.add(internalBS);
			}
			
			final Float[] is = new Float[size];// this.similarityMatrix[i].clone();
			Arrays.fill(is, 0F);
			BitSet bsI = bsList.get(i);
			weights.add(bsI.cardinality());
			sims.add(is);
			
			for (int j = 0; j < size; j++) {
				
				BitSet bsJ = bsList.get(j);
				int c = 0;
				if (i == j) {
					is[j] = 1F;
					continue;
				}
				for (int k = bsI.nextSetBit(0); k >= 0; k = bsI.nextSetBit(k + 1)) {
					for (int l = bsJ.nextSetBit(0); l >= 0; l = bsJ.nextSetBit(l + 1)) {
						is[j] += this.similarityMatrix[k][l];
						c++;
					}
				}
				if (c == 0) {
					throw new RuntimeException("Error: "+bsI + " "+bsJ);
				}
				is[j] /= c;
			}
		
			range.remove(i);
			TreeSet<Integer> sortColumn = this.sortColumn(range, is);
			range.add(i,i);
			indsBySim.add(sortColumn);
		}
		
		return upgmaLoop(weights, internalBSList, indsBySim, sims, size);
	}
	
	List<BitSet> UPGMA() {
		
		List<BitSet> bsList = new ArrayList<BitSet>(n);
		List<TreeSet<Integer>> indsBySim = new ArrayList<TreeSet<Integer>>(n);
		List<Float[]> sims = new ArrayList<Float[]>(n);
		List<Integer> range = Utils.getRange(n);
		List<Integer> weights = Utils.getOnes(n);
		
		for (int i = 0; i< n; i++) {
			BitSet bs = new BitSet();
			bs.set(i);
			bsList.add(bs);
			final Float[] is = this.similarityMatrix[i].clone();
			sims.add(is);
			range.remove(i);
			TreeSet<Integer> sortColumn = this.sortColumn(range, is);
			range.add(i,i);
			indsBySim.add(sortColumn);
		}
		
		return upgmaLoop(weights, bsList, indsBySim, sims, n);
	}

	private List<BitSet> upgmaLoop(List<Integer> weights, List<BitSet> bsList,
			List<TreeSet<Integer>> indsBySim, List<Float[]> sims, int left) {
		List<BitSet> ret = new ArrayList<BitSet>();
		while ( left > 2) {
			int closestI = -1;
			int closestJ = -1;
			Float bestHit = -1F;
			for (int i = 0; i < indsBySim.size(); i++) {
				if (indsBySim.get(i) == null)
					continue;
				int j = indsBySim.get(i).first();
				if (sims.get(i)[j] > bestHit) {
					bestHit = sims.get(i)[j];
					closestI = i;
					closestJ = j;
				}
			}
			BitSet bs = (BitSet) bsList.get(closestI).clone();
			bs.or(bsList.get(closestJ));
			bsList.set(closestJ,null);
			bsList.set(closestI,bs);
			
			Float[] jDist = sims.get(closestJ);
			Float[] iDist = sims.get(closestI).clone();
			for (int k = 0; k < sims.size(); k++) {
				if (k == closestJ || sims.get(k) == null) {
					continue;
				}
				
				if ( k != closestI) {
					Float newSimToI = (iDist[k] * weights.get(closestI) + jDist[k] * weights.get(closestJ))/( weights.get(closestI)+ weights.get(closestJ));
					
					indsBySim.get(k).remove(closestI);
					sims.get(k)[closestI] = newSimToI;
					indsBySim.get(k).add(closestI);
					
					indsBySim.get(closestI).remove(k);
					sims.get(closestI)[k] = newSimToI;
					indsBySim.get(closestI).add(k);
				}
			
				indsBySim.get(k).remove(closestJ);
				sims.get(k)[closestJ] = -1F;
				//indsBySim.get(k).add(closestJ);
			}
			
			sims.set(closestJ,null);
			indsBySim.set(closestJ,null);
			weights.set(closestI, weights.get(closestI)+weights.get(closestJ));
			weights.set(closestJ,null);
			ret.add(bs);
			left--;
		}
		return ret;
	}
	public void fillZero2D(Float[][] array) {
		for(int i = 0; i < array.length; i++) {
			for(int j = 0; j < array[0].length; j++) {
				array[i][j] = 0F;
			}
		}
	}
}
