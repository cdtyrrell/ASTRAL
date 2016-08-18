struct Intersects {
	int s0;
	int s1;
	int s2;
};
void addIntersects(struct Intersects * augend, struct Intersects * addend, struct Intersects * result) {
	result->s0 = augend->s0 + addend->s0;
	result->s1 = augend->s1 + addend->s1;
	result->s2 = augend->s2 + addend->s2;
}
void subtractIntersects(struct Intersects * minuend, struct Intersects * subtrahend, struct Intersects * result) {
	result->s0 = minuend->s0 - subtrahend->s0;
	result->s1 = minuend->s1 - subtrahend->s1;
	result->s2 = minuend->s2 - subtrahend->s2;
}
struct IntersectsStack {
	struct Intersects array [STACK_SIZE];
	int currentIndex; //index of the last valid element. -1 if empty.
};
void push(struct IntersectsStack * stack, struct Intersects * item) {
	stack->array[++(stack->currentIndex)].s0 = item->s0;
	stack->array[(stack->currentIndex)].s1 = item->s1;
	stack->array[(stack->currentIndex)].s2 = item->s2;
}
void pop(struct IntersectsStack * stack, struct Intersects * item) {
	item->s0 = stack->array[stack->currentIndex].s0;
	item->s1 = stack->array[stack->currentIndex].s1;
	item->s2 = stack->array[stack->currentIndex--].s2;
}
void get(struct IntersectsStack * stack, struct Intersects * item, int index) {
	item->s0 = stack->array[index].s0;
	item->s1 = stack->array[index].s1;
	item->s2 = stack->array[index].s2;
}
void clear(struct IntersectsStack * stack) {
	stack->currentIndex = -1;
}
long F(int a, int b, int c) {
	return ((long)(a + b + c - 3))*a*b*c;
}
struct Tripartition {
	__global long* cluster1;
	__global long* cluster2;
	__global long* cluster3;
};
struct Intersects * getSide(int in, struct Intersects * side, struct Tripartition * trip) {
	if (((trip->cluster1[SPECIES_WORD_LENGTH - 1 - in / LONG_BIT_LENGTH])>>(in%LONG_BIT_LENGTH))&1) {
		side->s0 = 1;
		side->s1 = 0;
		side->s2 = 0;
	}
	else if (((trip->cluster2[SPECIES_WORD_LENGTH - 1 - in / LONG_BIT_LENGTH])>>(in%LONG_BIT_LENGTH))&1) {
		side->s0 = 0;
		side->s1 = 1;
		side->s2 = 0;
	}
	else {
		side->s0 = 0;
		side->s1 = 0;
		side->s2 = 1;
	}

	return side;
}
int bitIntersectionSize(__global long input1[SPECIES_WORD_LENGTH], __global long input2[SPECIES_WORD_LENGTH]) {
	int out = 0;
	for (int i = 0; i < SPECIES_WORD_LENGTH; i++) {
		out += popcount(input1[i]&input2[i]);
	}
	return out;
}
__kernel void calcWeight(
	__global int* geneTreesAsInts,
	int geneTreesAsIntsLength,
	__global long* allArray,
	__global long* tripartitions,
	__global long* weightArray
){
	long weight = 0;
	struct Tripartition trip;
	int idx = get_global_id(0);
	trip.cluster1 = SPECIES_WORD_LENGTH * 3 * idx + tripartitions;
	trip.cluster2 = SPECIES_WORD_LENGTH * 3 * idx + SPECIES_WORD_LENGTH + tripartitions;
	trip.cluster3 = SPECIES_WORD_LENGTH * 3 * idx + SPECIES_WORD_LENGTH * 2 + tripartitions;
	
	struct Intersects allsides;
	allsides.s0 = 0;
	allsides.s1 = 0;
	allsides.s2 = 0;

	struct IntersectsStack stack;
	stack.currentIndex = -1;

	int newTree = 1;
	int counter = 0;
	int treeCounter = 0;
	for (; counter < geneTreesAsIntsLength; counter++) {

		if (newTree) {
			newTree = 0;

			allsides.s0 = bitIntersectionSize(&allArray[treeCounter * SPECIES_WORD_LENGTH], trip.cluster1);
			allsides.s1 = bitIntersectionSize(&allArray[treeCounter * SPECIES_WORD_LENGTH], trip.cluster2);
			allsides.s2 = bitIntersectionSize(&allArray[treeCounter * SPECIES_WORD_LENGTH], trip.cluster3);

			treeCounter++;

		}
		if (geneTreesAsInts[counter] >= 0) {
			struct Intersects side;
			push(&stack, getSide(geneTreesAsInts[counter], &side, &trip));
		}
		else if (geneTreesAsInts[counter] == INT_MIN) {
			clear(&stack);
			newTree = 1;
		}
		else if (geneTreesAsInts[counter] == -2) {
			struct Intersects side1;
			struct Intersects side2;
			struct Intersects newSide;
			struct Intersects side3;

			pop(&stack, &side1);
			pop(&stack, &side2);
			
			addIntersects(&side1, &side2, &newSide);

			push(&stack, &newSide);

			subtractIntersects(&allsides, &newSide, &side3);

			weight += 
				F(side1.s0, side2.s1, side3.s2) +
				F(side1.s0, side2.s2, side3.s1) +
				F(side1.s1, side2.s0, side3.s2) +
				F(side1.s1, side2.s2, side3.s0) +
				F(side1.s2, side2.s0, side3.s1) +
				F(side1.s2, side2.s1, side3.s0);
			/*
			weight += 
				((long)(side1.s0 + side2.s1 + side3.s2 - 3))*side1.s0*side2.s1*side3.s2 +
				((long)(side1.s0 + side2.s2 + side3.s1 - 3))*side1.s0*side2.s2*side3.s1 +
				((long)(side1.s1 + side2.s0 + side3.s2 - 3))*side1.s1*side2.s0*side3.s2 +
				((long)(side1.s1 + side2.s2 + side3.s0 - 3))*side1.s1*side2.s2*side3.s0 +
				((long)(side1.s2 + side2.s0 + side3.s1 - 3))*side1.s2*side2.s0*side3.s1 +
				((long)(side1.s2 + side2.s1 + side3.s0 - 3))*side1.s2*side2.s1*side3.s0;
				*/
			/*if(idx == 32800) {
				printf("|%d %d %d %d %d %d %d %d %d %d |", weight, side1.s0, side1.s1, side1.s2, side2.s0, side2.s1, side2.s2, side3.s0, side3.s1, side3.s2);
			}*/
		}
		else { //for polytomies
			struct IntersectsStack children;
			children.currentIndex = -1;
			struct Intersects newSide;
			newSide.s0 = 0;
			newSide.s1 = 0;
			newSide.s2 = 0;

			for (int i = geneTreesAsInts[counter]; i < 0; i++) {
				addIntersects(&newSide, &(stack.array[stack.currentIndex]), &newSide);
				pop(&stack, &(children.array[++children.currentIndex]));
			}
			
			push(&stack, &newSide);
			
			struct Intersects sideRemaining;
			subtractIntersects(&allsides, &newSide, &sideRemaining);

			if (sideRemaining.s0 != 0 || sideRemaining.s1 != 0 || sideRemaining.s2 != 0) {
				push(&children, &sideRemaining);
			}
			struct Intersects side1;
			struct Intersects side2;
			struct Intersects side3;

			for (int i = 0; i <= children.currentIndex; i++) {
				get(&children, &side1, i);

				for (int j = i + 1; j <= children.currentIndex; j++) {
					get(&children, &side2, j);

					if (children.currentIndex > 4) {
						if ((side1.s0 + side2.s0 == 0 ? 1 : 0) +
							(side1.s1 + side2.s1 == 0 ? 1 : 0) +
							(side1.s2 + side2.s2 == 0 ? 1 : 0) > 1)
							continue;
					}

					for (int k = j + 1; k <= children.currentIndex; k++) {
						get(&children, &side3, k);

						weight +=
							F(side1.s0, side2.s1, side3.s2) +
							F(side1.s0, side2.s2, side3.s1) +
							F(side1.s1, side2.s0, side3.s2) +
							F(side1.s1, side2.s2, side3.s0) +
							F(side1.s2, side2.s0, side3.s1) +
							F(side1.s2, side2.s1, side3.s0);
					}
				}
			}
		}
	}
	weightArray[idx] = weight;
}
