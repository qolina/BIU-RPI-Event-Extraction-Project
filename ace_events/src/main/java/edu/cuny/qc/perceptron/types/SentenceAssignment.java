package edu.cuny.qc.perceptron.types;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.uima.cas.CASException;

import ac.biu.nlp.nlp.ie.onthefly.input.SpecAnnotator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import edu.cuny.qc.ace.acetypes.AceEventMention;
import edu.cuny.qc.ace.acetypes.AceEventMentionArgument;
import edu.cuny.qc.ace.acetypes.AceMention;
import edu.cuny.qc.perceptron.core.ArgOMethod;
import edu.cuny.qc.perceptron.core.Controller;
import edu.cuny.qc.perceptron.featureGenerator.GlobalFeatureGenerator;
import edu.cuny.qc.perceptron.types.SentenceInstance.InstanceAnnotations;
import edu.cuny.qc.scorer.ScorerData;
import edu.cuny.qc.scorer.SignalMechanismsContainer;
import edu.cuny.qc.util.Logs;
import edu.cuny.qc.util.UnsupportedParameterException;

/**
 * For the (target) assignment, it should encode two types of assignment:
 * (1) label assignment for each token: refers to the event trigger classification 
 * (2) assignment for any sub-structure of the sentence, e.g. one assignment indicats that 
 * the second token is argument of the first trigger
 * in the simplest case, this type of assignment involves two tokens in the sentence
 * 
 * 
 * @author che
 *
 */
public class SentenceAssignment
{
	public static final String PAD_Trigger_Label = "O\t"; // pad for the intial state
	public static final String Default_Trigger_Label = PAD_Trigger_Label;
	public static final String Generic_Existing_Trigger_Label = "TRIGGER\t";
	public static final String Default_Argument_Label = "X\t";//"NON\t";
	public static final String Generic_Existing_Argument_Label = "ARG\t";
	public static final String Really_Generic_Existing_Label = "LBL";
	public static final String GLOBAL_LABEL = "GLOBAL";
	
	public static final String CURRENT_LABEL_MARKER = "\tcurrentLabel:";
	public static final String TRIGGER_LABEL_MARKER = "triggerLabel:";
	public static final String ARG_ROLE_MARKER = "\tArgRole:";
	public static final String IS_ARG_MARKER = "IsArg";
	public static final List<String> ALL_FEATURE_NAME_MARKERS = ImmutableList.of(CURRENT_LABEL_MARKER, TRIGGER_LABEL_MARKER, ARG_ROLE_MARKER, IS_ARG_MARKER);
	
	//public static final Pattern methodPattern = Pattern.compile("G\\w(.+)P"); //for "a"\"b"
	public static final Pattern methodPattern = Pattern.compile("G(.+)P");

	// {signalName : {label : {moreParams : featureName}}}
	public static Map<String, Map<String, Map<String, String>>> signalToFeature = Maps.newTreeMap();
	public static List<String> storedLabels;
	public static int numStoredTriggerLabels = 0;
	public static int numStoredArgLabels = 0;
	public static final int SIGNALS_TO_STORE = 100;
	public static final int TRIGGER_LABELS_TO_STORE = 3;
	public static final int ARGUMENT_LABELS_TO_STORE = 3;

	public static final String BIAS_FEATURE = "BIAS_FEATURE";
	
	public /*static*/ final BigDecimal FEATURE_POSITIVE_VAL;
	public /*static*/ final BigDecimal FEATURE_NEGATIVE_VAL;

	/**
	 * the index of last processed (assigned/searched) token
	 */
	public int state = -1;
	
	public String sentInstId = "?";
	static {
		//System.err.println("??? SentenceAssignment: Assumes binary labels O,ATTACK (around oMethod)");
		System.err.println("??? SentenceAssignment: Edge features are currently excluded, until I stabalize a policy with triggers. Then some policy should be decided for edges (that should handle, for example, the fact that if only the guess has some trigger that the gold doesn't have, then it would physically have more features than target (not same features just with different scores, like in the triggers' case), and this violated my assumption that guess and gold have the same features. Relevant methods: equals() (3 methods), makeEdgeLocalFeature(), BeamSearch.printBeam (check compatible)");
		System.err.println("??? SentenceAssignment:    7.8.14 Update to previous comment: I am not sure I entriely understand the thing with 'more features', but I do think this is solved when I decided that ALL argcands have their signals calculated with ALL roles. This was initially decided since we need these signals even for an O assgnemtn, but I think this also solves this case. We'll see.");
		System.err.println("??? SentenceAssignment: Perhaps should do P- also for Edge and Global features");
		System.err.println("??? SentenceAssignment: GLOBAL FEATURES ARE NOT IMPORTED YET!!!");
		//System.err.println("??? SentenceAssignment: in makeEdgeLocal..(), I am pretty sure that I am doing wrong use of all the superfluous arguments of makeFeatures() (The ones that documents signals and such). I just use it sorta like in triggers, but at least some of it has GOT to be wrong.");
		System.err.println("??? SentenceAssignment: makeFeature() still doesn't support log stuff normaly. I probably have to add a 'String roleLabel' parameter, where the current 'label' param wil become 'triggerLabel', and add another level to the Map-Map-Map that will also save the role (or PRD for the predicate). But the question is - what is the signal name? Maybe I need to pass several, since an O is affected by all roles... I dunno, think about it :)");
		System.err.println("??? SentenceAssignment: we are re-calculating free arg features during a dependent-arg loop. Shouldn't be TOO time costly, and keeps the code simpler.");
		System.err.println("??? SentenceAssignment: Remove the check that two role sets for dependent and free are the same.");
	}
	
	public static String getGenericTriggerLabel(String label) {
		if (label.equals(Default_Trigger_Label)) {
			return Default_Trigger_Label;
		}
		else {
			return Generic_Existing_Trigger_Label;
		}
	}
	
	public static String getGenericArgumentLabel(String label) {
		if (label.equals(Default_Argument_Label)) {
			return Default_Argument_Label;
		}
		else {
			return Generic_Existing_Argument_Label;
		}
	}
	
	public static String getReallyGenericLabel(String label) {
		if (label.equals(Default_Trigger_Label) || label.equals(Default_Argument_Label)) {
			return Default_Trigger_Label.trim();
		}
		else {
			return Really_Generic_Existing_Label;
		}
	}
	
	public static String stripLabel(String featureName) {
		String result = featureName;
		for (String marker : ALL_FEATURE_NAME_MARKERS) {
			result = result.replaceFirst(String.format("%s\\S+",  marker), "");
		}
		return result;
	}
	
	public void retSetState()
	{
		state = -1;
	}
	
	public int getState()
	{
		return state;
	}
	
	/// DEBUG
	public static int ordGlobal = 0;
	public int ord;
	///
	
	/*
	 * indicates if it violates gold-standard, useful for learning
	 */
	boolean violate = false;
	
	// the alphabet of the label for each node (token), shared by the whole application
	// they should be consistent with SentenceInstance object
	public Alphabet nodeTargetAlphabet;
	
	// the alphabet of the label for each edge (trigger-->argument link), shared by the whole application
	// they should be consistent with SentenceInstance object
	public Alphabet edgeTargetAlphabet;

	// the alphabet of features, shared by the whole application
	public Alphabet featureAlphabet;
	
	public Controller controller;
	
	//public TypesContainer types;
	
	//public transient SentenceInstance inst = null;
	public transient SentenceAssignment target = null;
	public List<AceMention> eventArgCandidates = null;
	
	// the feature vector of the current assignment
	public FeatureVectorSequence featVecSequence;
	
	// the score of the assignment, it can be partial score when the assignment is not complete
	protected BigDecimal score = BigDecimal.ZERO;
	protected List<BigDecimal> partial_scores;
	
	public SignalMechanismsContainer signalMechanismsContainer;
	
	//public Map<Integer, Map<String, List<SignalInstance>>> featureToSignal; 
	public Map<Integer, Map<String, String>> signalsToValues; 
	public List<BigDecimal> getPartialScores() {
		return partial_scores;
	}
	
	public FeatureVector getFV(int index)
	{
		return featVecSequence.get(index);
	}
	
	public FeatureVector getCurrentFV()
	{
		return featVecSequence.get(state);
	}
	
	public FeatureVectorSequence getFeatureVectorSequence()
	{
		return featVecSequence;
	}
	
	public void addFeatureVector(FeatureVector fv)
	{
		featVecSequence.add(fv);
	}
	
	/**
	 * as the search processed, increament the state for next token
	 * creat a new featurevector for this state
	 */
	public void incrementState()
	{
		state++;
		FeatureVector fv = new FeatureVector();
		this.addFeatureVector(fv);
		this.partial_scores.add(BigDecimal.ZERO);
	}
	
	public void increaseStateTo(int newState) {
		if (newState < state) {
			throw new IllegalStateException("Tried to increase state to " + newState + ", but it's already in " + state);
		}
		else {
			for (int n=state; n<newState; n++) {
				incrementState();
			}
		}
		//NOTE that if newState==state, nothing happen and we return silently
	}
	
	/**
	 * assignment to each node, node-->assignment
	 */
	public Vector<Integer> nodeAssignment;
	
	public Vector<Integer> getNodeAssignment()
	{
		return nodeAssignment;
	}
	
	/**
	 * assignment to each argument edge trigger-->arg --> assignment
	 */
	protected Map<Integer, Map<Integer, Integer>> edgeAssignment;
	
	/**
	 * deep copy an assignment
	 */
	public SentenceAssignment clone()
	{
		// shallow copy the alphabets
		SentenceAssignment assn = new SentenceAssignment(/*types,*/ signalMechanismsContainer, eventArgCandidates, target, nodeTargetAlphabet, edgeTargetAlphabet, featureAlphabet, controller);
		
		// shallow copy the assignment
		assn.nodeAssignment = (Vector<Integer>) this.nodeAssignment.clone();
		// deep copy the edge assignment for the last element 
		// (this is because in the beam search, we need expand the last statement for arguments labeling)
		for(Integer key : this.edgeAssignment.keySet())
		{
			Map<Integer, Integer> edgeMap = this.edgeAssignment.get(key);
			if(edgeMap != null)
			{
				if(key < this.getState())
				{
					assn.edgeAssignment.put(key, edgeMap);
				}
				else // deep copy the last element
				{
					Map<Integer, Integer> new_edgeMap = new HashMap<Integer, Integer>();
					new_edgeMap.putAll(edgeMap);
					assn.edgeAssignment.put(key, new_edgeMap);
				}
			}
		}
		
		// deep copy the feature vector sequence for the last element
		assn.featVecSequence = this.featVecSequence.clone2();
		
		// deep copy attributes
		assn.state = this.state;
		assn.score = this.score;
		//assn.local_score = this.local_score;
		assn.partial_scores.addAll(this.partial_scores);
		
		//assn.featureToSignal = new HashMap<Integer, Map<String, List<SignalInstance>>>();
		assn.signalsToValues = new HashMap<Integer, Map<String, String>>();
		for (Entry<Integer, Map<String, String>> entry : signalsToValues.entrySet()) {
			Map<String, String> map = new HashMap<String, String>();
			assn.signalsToValues.put(new Integer(entry.getKey()), map);
			for(Entry<String, String> entry2 : entry.getValue().entrySet()) {
				map.put(entry2.getKey(), entry2.getValue());
			}
		}
		return assn;
	}
	
	public Map<Integer, Map<Integer, Integer>> getEdgeAssignment()
	{
		return edgeAssignment;
	}
	
	/**
	 * get the node label of the current node
	 * @return
	 */
	public String getLabelAtToken(int i)
	{
		assert(i <= state);
		
		String label;
		if(i >= 0 )
		{
			label = (String) nodeTargetAlphabet.lookupObject(nodeAssignment.get(i));
		}
		else
		{
			label = PAD_Trigger_Label;
		}
		return label;
	}
	
	/**
	 * get the node label of the current node
	 * @return
	 */
	public String getCurrentNodeLabel()
	{
		String label;
		if(state >= 0 )
		{
			label = (String) nodeTargetAlphabet.lookupObject(nodeAssignment.get(state));
		}
		else
		{
			label = PAD_Trigger_Label;
		}
		return label;
	}
	
	/**
	 * set current node label
	 * @return
	 */
	public void setCurrentNodeLabel(int index)
	{
		if(state >= 0 )
		{
			if(nodeAssignment.size() <= state)
			{
				nodeAssignment.setSize(state + 1);
			}
			this.nodeAssignment.set(state, index);
		}
	}
	
	/**
	 * set current node label
	 * @return
	 */
	public void setCurrentNodeLabel(String label)
	{
		if(state >= 0 )
		{
			int index = nodeTargetAlphabet.lookupIndex(label);
			if(nodeAssignment.size() <= state)
			{
				nodeAssignment.setSize(state + 1);
			}
			this.nodeAssignment.set(state, index);
		}
	}
	
	public Map<Integer, Integer> getCurrentEdgeLabels()
	{
		Map<Integer, Integer> map = this.edgeAssignment.get(state);
		if(map == null)
		{
			map = new HashMap<Integer, Integer>();
			this.edgeAssignment.put(state, map);
		}
		return map;
	}
	
	/**
	 * set current node edge
	 * @return
	 */
	public void setCurrentEdgeLabel(int arg_idx, String label)
	{
		if(state >= 0 )
		{
			int index = edgeTargetAlphabet.lookupIndex(label);
			Map<Integer, Integer> map = this.edgeAssignment.get(state);
			if(map == null)
			{
				map = new HashMap<Integer, Integer>();
			}
			map.put(arg_idx, index);
			this.edgeAssignment.put(state, map);
		}
	}
	
	/**
	 * set current node edges
	 * @return
	 */
	public void setCurrentEdges(Map<Integer, Integer> edges)
	{
		if(state >= 0 )
		{
			Map<Integer, Integer> map = this.edgeAssignment.get(state);
			if(map == null)
			{
				map = new HashMap<Integer, Integer>();
			}
			map.putAll(edges);
			this.edgeAssignment.put(state, map);
		}
	}
	
	/**
	 * set current node label
	 * @return
	 */
	public void setCurrentEdgeLabel(int arg_idx, int label)
	{
		if(state >= 0 )
		{
			Map<Integer, Integer> map = this.edgeAssignment.get(state);
			if(map == null)
			{
				map = new HashMap<Integer, Integer>();
			}
			map.put(arg_idx, label);
			this.edgeAssignment.put(state, map);
		}
	}
	
	public SentenceAssignment(/*TypesContainer types,*/SignalMechanismsContainer signalMechanismsContainer, List<AceMention> eventArgCandidates, SentenceAssignment target, Alphabet nodeTargetAlphabet, Alphabet edgeTargetAlphabet, Alphabet featureAlphabet, Controller controller)
	{
		this.nodeTargetAlphabet = nodeTargetAlphabet;
		this.edgeTargetAlphabet = edgeTargetAlphabet;
		this.featureAlphabet = featureAlphabet;
		this.controller = controller;
		//this.types = types;
		//this.inst = instance;
		this.eventArgCandidates = eventArgCandidates;
		this.target = target;
		
		nodeAssignment = new Vector<Integer>();
		edgeAssignment = new HashMap<Integer, Map<Integer, Integer>>();
		
		featVecSequence = new FeatureVectorSequence();
		partial_scores = new ArrayList<BigDecimal>();
		signalsToValues = new HashMap<Integer, Map<String, String>>();
		this.signalMechanismsContainer = signalMechanismsContainer;

		/// DEBUG
		ord = ordGlobal;
		ordGlobal++;
		///
		
		if (this.controller.oMethod.startsWith("G")) {
			
			FEATURE_POSITIVE_VAL = BigDecimal.ONE;
			Matcher matcher1 = methodPattern.matcher(this.controller.oMethod);
			if (matcher1.find()) {
				String negValStr = matcher1.group(1);
				FEATURE_NEGATIVE_VAL = new BigDecimal(negValStr);
			}
			else {
				throw new IllegalStateException("G method must get some number for neg_val before P+\\-, got: " + this.controller.oMethod);
			}
		}
		else {
			throw new IllegalStateException("Supporting only 'G' oMethdos for now! Got: " + this.controller.oMethod);
		}
	}
	
	/**
	 * given an labeled instance, create a target assignment as ground-truth
	 * also create a full featureVectorSequence
	 * @param inst
	 */
	public SentenceAssignment(SentenceInstance inst, SignalMechanismsContainer signalMechanismsContainer)
	{
		this(/*inst.types,*/signalMechanismsContainer, inst.eventArgCandidates, inst.target, inst.nodeTargetAlphabet, inst.edgeTargetAlphabet, inst.featureAlphabet, inst.controller);
		
		this.sentInstId = inst.sentInstID;
		
		for(int i=0; i < inst.size(); i++)
		{
			int label_index = this.nodeTargetAlphabet.lookupIndex(Default_Trigger_Label);
			this.nodeAssignment.add(label_index);
			this.incrementState();
		}
		
		// use sentence data to assign event/arg tags
		for(AceEventMention mention : inst.eventMentions)
		{
			Vector<Integer> headIndices = mention.getHeadIndices();
			
			// for event, only pick up the first token as trigger
			int trigger_index = headIndices.get(0);  
			// ignore the triggers that are with other POS
			if(!inst.types.isPossibleTriggerByPOS(inst, trigger_index))
			{	
				continue;
			}
			int feat_index = this.nodeTargetAlphabet.lookupIndex(mention.getSubType());
			this.nodeAssignment.set(trigger_index, feat_index);
			
			Map<Integer, Integer> arguments = edgeAssignment.get(trigger_index);
			if(arguments == null)
			{
				arguments = new HashMap<Integer, Integer>();
				edgeAssignment.put(trigger_index, arguments);
			}
			
			// set default edge label between each trigger-entity pair
			for(int can_id=0; can_id < inst.eventArgCandidates.size(); can_id++)
			{
				AceMention can = inst.eventArgCandidates.get(can_id);
				// ignore entity that are not compatible with the event
				if(inst.types.isEntityTypeEventCompatible(mention.getSubType(), can.getType()))
				{
					feat_index = this.edgeTargetAlphabet.lookupIndex(Default_Argument_Label);
					arguments.put(can_id, feat_index);
				}
			}
			
			// set argument role labels
			for(AceEventMentionArgument arg : mention.arguments)
			{
				AceMention arg_mention = arg.value;
				int arg_index = inst.eventArgCandidates.indexOf(arg_mention);
				/// DEBUG
				if (arg.role.startsWith("Time")) {
					System.out.printf("SentenceAssignment: %s:%s %s(%s): %s\n", sentInstId, arg_index, arg.role, feat_index, arg_mention);
				}
				////
				feat_index = this.edgeTargetAlphabet.lookupIndex(arg.role);
				arguments.put(arg_index, feat_index);
				
				/// DEBUG
//				if (arg.role.equals("Attacker")) {
//					System.out.printf("SentenceAssignment: %s:%s Attacker(%s): %s\n", sentInstId, arg_index, feat_index, arg_mention);
//				}
				////
			}
		}
	}
	
	/**
	 * Not sure what this stuff does or why it's here -
	 * All I know is that this had to happen in the end of building target, but I needed to
	 *   split it from the c-tor, so it's here. That's all.
	 * @param inst
	 */
	public void blahBlah(SentenceInstance inst) {
		
		// create featureVectorSequence
		if (!controller.lazyTargetFeatures) {
			makeAllFeatures(inst);
		}
	}
	
	public void makeAllFeatures(SentenceInstance inst) {
		for(int i=0; i<=state; i++)
		{
			makeAllFeatureForSingleState(inst, i, inst.learnable, inst.learnable);
		}
	}
	
	private void makeAllFeatureForSingleState(SentenceInstance problem, int i, boolean addIfNotPresent,
			boolean useIfNotPresent)
	{
		// make basic bigram features for event trigger
		this.makeNodeFeatures(problem, i, addIfNotPresent, useIfNotPresent);
		// make basic features of the argument-trigger link
		this.makeEdgeFeatures(problem, i, addIfNotPresent, useIfNotPresent);
		if(problem.controller.useGlobalFeature)
		{
			// make various global features
			this.makeGlobalFeatures(problem, i, addIfNotPresent, useIfNotPresent);
		}
	}

	/**
	 * extract global features from the current assignment
	 * @param assn
	 * @param problem
	 */
	public void makeEdgeFeatures(SentenceInstance problem, int index, boolean addIfNotPresent, 
			boolean useIfNotPresent)
	{
		// node label
		String nodeLabel = this.getLabelAtToken(index);
		if(!isArgumentable(nodeLabel))
		{
			return;
		}
		
		Map<Integer, Integer> edge = edgeAssignment.get(index);
		if(edge == null)
		{
			return;
		}
		
		for(Integer key : edge.keySet())
		{
			// edge label
			makeEdgeLocalFeature(problem, index, addIfNotPresent, key, useIfNotPresent);
		}
	}

	public void makeEdgeLocalFeature(SentenceInstance problem, int index, boolean addIfNotPresent, 
			int entityIndex, boolean useIfNotPresent)
	{	
		try {
			if (!controller.useArguments) {
				return;
			}
			
			if(this.edgeAssignment.get(index) == null)
			{
				// skip assignments that don't have edgeAssignment for index-th node
				return;
			}
			Integer edgeLabelIndx = this.edgeAssignment.get(index).get(entityIndex);
			if(edgeLabelIndx == null)
			{
				return;
			}
			String edgeLabel = (String) this.edgeTargetAlphabet.lookupObject(edgeLabelIndx);
			String genericEdgeLabel = getGenericArgumentLabel(edgeLabel);
			// if the argument role is NON, then do not produce any feature for it
			if(controller.argOMethod==ArgOMethod.SKIP_O && edgeLabel.equals(SentenceAssignment.Default_Argument_Label))
			{
				return; 
			}
			
			String nodeLabel = getLabelAtToken(index);
			List<Map<String, List<Map<String, Map<ScorerData, SignalInstance>>>>> edgeDependentSignals   = (List<Map<String,  List<Map<String, Map<ScorerData, SignalInstance>>>>>) problem.get(InstanceAnnotations.EdgeDependentTextSignals);
			Map<String, List<Map<String, Map<ScorerData, SignalInstance>>>> edgeFreeSignals   = (Map<String,  List<Map<String, Map<ScorerData, SignalInstance>>>>) problem.get(InstanceAnnotations.EdgeFreeTextSignals);
	
			//Integer nodeNum = problem.types.triggerTypes.get(nodeLabel);
			//Integer edgeNum = problem.types.argumentRoles.get(nodeLabel).get(edgeLabel);
			//Map<ScorerData, SignalInstance> signals = edgeSignals.get(index).get(nodeLabel).get(entityIndex).get(edgeLabel);
			Map<String, Map<ScorerData, SignalInstance>> signalsForEntityDependent = edgeDependentSignals.get(index).get(nodeLabel).get(entityIndex);
			
			Map<String, Map<ScorerData, SignalInstance>> signalsForEntityFree = edgeFreeSignals.get(nodeLabel).get(entityIndex);
			
			// Just add the free signals to the dependent ones. Yes, it means these will be calculated multiple times, but it shouldn't be too time costly
			/// DEBUG
			if (!signalsForEntityDependent.keySet().equals(signalsForEntityFree.keySet())) {
				throw new IllegalStateException(String.format("got different role sets for arg signals!: dependent=%s, free=%s", signalsForEntityDependent.keySet(), signalsForEntityFree.keySet()));
			}
			////
//			Set<String> roles = signalsForEntityDependent.keySet();
//			roles.addAll(signalsForEntityFree.keySet());
			Map<String, Map<ScorerData, SignalInstance>> allSignalsForEntity = new HashMap<String, Map<ScorerData, SignalInstance>>(signalsForEntityDependent.keySet().size());
			for (String role : signalsForEntityDependent.keySet()) {
				Map<ScorerData, SignalInstance> perRole = new HashMap<ScorerData, SignalInstance>();
				allSignalsForEntity.put(role, perRole);
				perRole.putAll(signalsForEntityDependent.get(role));
				perRole.putAll(signalsForEntityFree.get(role));
			}
//			Iterator<Entry<String, Map<ScorerData, SignalInstance>>> allSignalsForEntityIterator =
//					Iterators.concat(signalsForEntityDependent.entrySet().iterator(), signalsForEntityFree.entrySet().iterator());
			
			/// DEBUG
//			System.out.printf("  ---SentenceAssignment.EdgeLocal: index=%s, nodeLabel=%s, entityIndex=%s\n       dependentSignals(%s): %s\n       freeSignals(%s): %s\n       allSignals(%s): %s\n",
//					index, nodeLabel, entityIndex, signalsForEntityDependent.size(), signalsForEntityDependent, signalsForEntityFree.size(), signalsForEntityFree, allSignalsForEntity.size(), allSignalsForEntity);
			////
						
			if (this.controller.oMethod.startsWith("G")) {
				if (genericEdgeLabel == Generic_Existing_Argument_Label) {
					Map<ScorerData, SignalInstance> signalsForRole = allSignalsForEntity.get(edgeLabel);
					
					/// DEBUG
					if (signalsForRole == null || signalsForRole.values() == null) {
						System.err.printf("\n\n signalsForRole == null!!!\n\n");
					}
					////
					
					for (SignalInstance signal : signalsForRole.values()) {
						List<SignalInstance> signals = Arrays.asList(new SignalInstance[] {signal});
						makeEdgeLocalFeatureInner(signals, signal.positive, signal.getName(), genericEdgeLabel, index, entityIndex, edgeLabel, addIfNotPresent, useIfNotPresent);
					}
				}
				else { //genericEdgeLabel == Default_Argument_Label
					if (this.controller.argOMethod==ArgOMethod.DUPLICATE_BY_ROLE) { //duplicate by role!
						Map<ScorerData, SignalInstance> signalsForRole = allSignalsForEntity.get(problem.associatedRole);
						
						for (SignalInstance signal : signalsForRole.values()) {
							List<SignalInstance> signals = Arrays.asList(new SignalInstance[] {signal});
							makeEdgeLocalFeatureInner(signals, signal.positive, signal.getName(), genericEdgeLabel, index, entityIndex, edgeLabel, addIfNotPresent, useIfNotPresent);
						}
					}
					else if (this.controller.argOMethod==ArgOMethod.OR_ALL) { //don't duplicate by role!
						for (ScorerData data : signalMechanismsContainer.argumentScorers) {
							List<SignalInstance> allSignalsAllRolesSameScorer = Lists.newArrayListWithCapacity(allSignalsForEntity.keySet().size());
							for (String role : allSignalsForEntity.keySet()) {
								Map<ScorerData, SignalInstance> signalsOfRole = allSignalsForEntity.get(role);
								SignalInstance signalOfRoleAndScorer = signalsOfRole.get(data);
								/// DEBUG
								if (signalOfRoleAndScorer == null) {
									System.out.printf("\n\n\n*** Got null signal! signal=%s, data=%s, role=%s\n\n\n\n", signalOfRoleAndScorer, data, role);
								}
								///
								allSignalsAllRolesSameScorer.add(signalOfRoleAndScorer);
							}
							// "or" method - the value is true if ANY of the signals' value is true
							boolean boolValue = false;
							for (SignalInstance signal : allSignalsAllRolesSameScorer) {
								if (signal.positive) {
									boolValue = true;
									break;
								}
							}
							String signalName = allSignalsAllRolesSameScorer.iterator().next().getName();
							makeEdgeLocalFeatureInner(allSignalsAllRolesSameScorer, boolValue, signalName, genericEdgeLabel, index, entityIndex, edgeLabel, addIfNotPresent, useIfNotPresent);
						}
					}
					else {
						throw new IllegalStateException("Got unsupported argOMethod: " + this.controller.argOMethod);
					}
				}
			}
			else {
				// Yeah, I don't really care about other methods right now :)
				throw new RuntimeException("Currently not supporting non-G methods");				
			}
		}
		catch (Exception e) {
			throw new RuntimeException(String.format("Exception when building edge features, index=%s, entityIndex=%s, SentenceInstance=%s", index, entityIndex, problem), e);
		}
	}
	
	private void makeEdgeLocalFeatureInner(List<SignalInstance> signals, boolean signalValue, String signalName, String genericEdgeLabel, int index, int entityIndex, String edgeLabel, boolean addIfNotPresent, boolean useIfNotPresent) {
		BigDecimal featureValuePositive = signalValue ? FEATURE_POSITIVE_VAL : FEATURE_NEGATIVE_VAL;
		
		String signalFullStr = "EdgeLocalFeature:\t" + signalName;
		String featureStrPositive = signalFullStr + "\t" + "P+\t" + CURRENT_LABEL_MARKER + genericEdgeLabel;
		
		makeFeature(featureStrPositive, this.getFV(index), featureValuePositive, index, entityIndex, signals, signalFullStr, edgeLabel, "P+", addIfNotPresent, useIfNotPresent);
		
		if (this.controller.oMethod.contains("P+")) {
			// do nothing, we did P+ before and nothing to do further
		}
		else if (this.controller.oMethod.contains("P-")) {
			BigDecimal featureValueNegative = signalValue ? FEATURE_NEGATIVE_VAL : FEATURE_POSITIVE_VAL;
			String featureStrNegative = signalFullStr + "\t" + "P-\t" + CURRENT_LABEL_MARKER + genericEdgeLabel;
			makeFeature(featureStrNegative, this.getFV(index), featureValueNegative, index, entityIndex, signals, signalFullStr, edgeLabel, "P-", addIfNotPresent, useIfNotPresent);
		}
		else {
			throw new IllegalStateException("Method G must explicitly state P+ or P-, got: " + this.controller.oMethod);
		}

	}
	
	/**
	 * This type of feature applies in each step of trigger classification
	 * @param problem
	 * @param index
	 * @param addIfNotPresent
	 * @param useIfNotPresent
	 */
	public void makeGlobalFeaturesTrigger(SentenceInstance problem, int index, boolean addIfNotPresent,
			boolean useIfNotPresent)
	{
		FeatureVector fv = this.getFV(index);
		List<String> featureStrs = GlobalFeatureGenerator.get_global_features_triggers(problem, index, this);
		for(String feature : featureStrs)
		{
			String featureStr = "TriggerLevelGlobalFeature:\t" + feature;
			makeFeature(featureStr, fv, null, null, null, featureStr, GLOBAL_LABEL, null, addIfNotPresent, useIfNotPresent);
		}
	} 
	
	/**
	 * this is global feature applicable when arugment searching in a node is complete
	 * @param problem
	 * @param index
	 * @param addIfNotPresent
	 */
	public void makeGlobalFeaturesComplete(SentenceInstance problem, int index, boolean addIfNotPresent,
			boolean useIfNotPresent)
	{
		FeatureVector fv = this.getFV(index);
		List<String> featureStrs = GlobalFeatureGenerator.get_global_features_node_level_omplete(problem, index, this);
		for(String feature : featureStrs)
		{
			String featureStr = "NodeLevelGlobalFeature:\t" + feature;
			makeFeature(featureStr, fv, null, null, null, featureStr, GLOBAL_LABEL, null, addIfNotPresent, useIfNotPresent);
		}
	}
	
	/**
	 * this version of global features are added when each step of argument searching
	 * @param problem
	 * @param index
	 * @param entityIndex
	 * @param addIfNotPresent
	 */
	public void makeGlobalFeaturesProgress(SentenceInstance problem, int index, int entityIndex, boolean addIfNotPresent,
			boolean useIfNotPresent)
	{
		FeatureVector fv = this.getFV(index);
		List<String> featureStrs = GlobalFeatureGenerator.get_global_features_node_level(problem, index, this, entityIndex);
		for(String feature : featureStrs)
		{
			String featureStr = "NodeLevelGlobalFeature:\t" + feature;
			makeFeature(featureStr, fv, null, null, null, featureStr, GLOBAL_LABEL, null, addIfNotPresent, useIfNotPresent);
		}
		featureStrs = GlobalFeatureGenerator.get_global_features_sent_level(problem, index, this, entityIndex);
		for(String feature : featureStrs)
		{
			String featureStr = "SentLevelGlobalFeature:\t" + feature;
			makeFeature(featureStr, fv, null, null, null, featureStr, GLOBAL_LABEL, null, addIfNotPresent, useIfNotPresent);
		}
		
	}
	
	/**
	 * this version of global features are added when argument searching for each node is completed 
	 * assume the argument search in token i is finished, then fill-in global features in token i
	 * @param problem
	 * @param i
	 * @param addIfNotPresent
	 */
	public void makeGlobalFeatures(SentenceInstance problem, int index, boolean addIfNotPresent, 
			boolean useIfNotPresent)
	{
		if(this.edgeAssignment.get(index) == null)
		{
			return;
		}
		makeGlobalFeaturesComplete(problem, index, addIfNotPresent, useIfNotPresent);
		for(int entityIndex : edgeAssignment.keySet())
		{
			makeGlobalFeaturesProgress(problem, index, entityIndex, addIfNotPresent, useIfNotPresent);
		}
	}
	
	public void makeNodeFeatures(SentenceInstance problem, int i, boolean addIfNotPresent, boolean useIfNotPresent)
	{
		try {
			// make node feature (bigram feature)
			List<Map<String, Map<ScorerData, SignalInstance>>> tokens = (List<Map<String, Map<ScorerData, SignalInstance>>>) problem.get(InstanceAnnotations.NodeTextSignalsBySpec);
			/// DEBUG
			if (tokens == null) {
				System.out.println(problem);
			}
			////
			Map<String, Map<ScorerData, SignalInstance>> token = tokens.get(i);
			//String previousLabel = this.getLabelAtToken(i-1);
			String label = this.getLabelAtToken(i);
			String genericLabel = getGenericTriggerLabel(label);
			
			if(this.controller.order >= 1)
			{
				throw new UnsupportedParameterException("order >= 1");
			}
			else // order = 0
			{
	//			Map<String, SignalInstance> signalsOfLabel = token.get(label);
	//			for (SignalInstance signal : signalsOfLabel.values()) {
	//				BigDecimal featureValue = signal.positive ? FEATURE_POSITIVE_VAL : FEATURE_NEGATIVE_VAL;
	//				//String featureStr = "BigramFeature:\t" + signal.name;
	//				String featureStr = "BigramFeature:\t" + signal.name + "\t" + LABEL_MARKER + genericLabel;
	//				List<SignalInstance> signals = Arrays.asList(new SignalInstance[] {signal});
	//				makeFeature(featureStr, this.getFV(i), featureValue, i, signals, addIfNotPresent, useIfNotPresent);
	//			}
				
				if (this.controller.oMethod.startsWith("G")) {
					// We don't check what is the label of this token, as the feature value is always according
					// to the associated spec, even when the token's label is O.
					// The only place in which the current label is expressed, is the "genericLabel" that is
					// added to the feature string
					try {
						String associatedSpecLabel = SpecAnnotator.getSpecLabel(problem.associatedSpec);
	//					/// DEBUG
	//					if (associatedSpecLabel == null || token == null) {
	//						System.err.printf("associatedSpecLabel=%s, token=%s\n", associatedSpecLabel, token);
	//					}
	//					///
						//Map<ScorerData, SignalInstance> signalsOfLabel = token.get(associatedSpecLabel);
						
						//Integer associatedSpecNum = problem.types.triggerTypes.get(associatedSpecLabel);
						Map<ScorerData, SignalInstance> signalsOfLabel = token.get(associatedSpecLabel);//token.get(associatedSpecNum);
						
	//					/// DEBUG
//						if (problem.sentInstID.equals("5b") && i==3) {
//							System.err.printf("\n\n\n\nand again...\n\n\n");
//						}
	//					///
						
						for (SignalInstance signal : signalsOfLabel.values()) {
							List<SignalInstance> signals = Arrays.asList(new SignalInstance[] {signal});
							BigDecimal featureValuePositive = signal.positive ? FEATURE_POSITIVE_VAL : FEATURE_NEGATIVE_VAL;
							
							String signalFullStr = "BigramFeature:\t" + signal.getName();
							String featureStrPositive = signalFullStr + "\t" + "P+\t" + CURRENT_LABEL_MARKER + genericLabel;
							
							makeFeature(featureStrPositive, this.getFV(i), featureValuePositive, i, null, signals, signalFullStr, label, "P+", addIfNotPresent, useIfNotPresent);
							
							if (this.controller.oMethod.contains("P+")) {
								// do nothing, we did P+ before and nothing to do further
							}
							else if (this.controller.oMethod.contains("P-")) {
								BigDecimal featureValueNegative = signal.positive ? FEATURE_NEGATIVE_VAL : FEATURE_POSITIVE_VAL;
								String featureStrNegative = signalFullStr + "\t" + "P-\t" + CURRENT_LABEL_MARKER + genericLabel;
								makeFeature(featureStrNegative, this.getFV(i), featureValueNegative, i, null, signals, signalFullStr, label, "P-", addIfNotPresent, useIfNotPresent);
							}
							else {
								throw new IllegalStateException("Method G must explicitly state P+ or P-, got: " + this.controller.oMethod);
							}
						}
					} catch (CASException e) {
						throw new RuntimeException(e);
					}
	
				}
				else {
					// Yeah, I don't really care about other methods right now :)
					throw new RuntimeException("Currently not supporting non-G methods");
					
//					if (genericLabel == Generic_Existing_Trigger_Label) {
//						Integer specNum = problem.types.triggerTypes.get(label);
//						Map<ScorerData, SignalInstance> signalsOfLabel = token.get(specNum);
//						for (SignalInstance signal : signalsOfLabel.values()) {
//							List<SignalInstance> signals = Arrays.asList(new SignalInstance[] {signal});
//							BigDecimal featureValue = signal.positive ? FEATURE_POSITIVE_VAL : FEATURE_NEGATIVE_VAL;
//							String featureStr = null;
//							if (this.controller.oMethod.startsWith("E")) {
//								featureStr = "BigramFeature:\t" + signal.getName();// + "\t" + LABEL_MARKER + genericLabel;
//							}
//							else {
//								featureStr = "BigramFeature:\t" + signal.getName() + "\t" + CURRENT_LABEL_MARKER + genericLabel;
//							}
//							makeFeature(featureStr, this.getFV(i), featureValue, i, signals, addIfNotPresent, useIfNotPresent);
//						}
//					}
//					else if (this.controller.oMethod.startsWith("E")) { //genericLabel == Default_Trigger_Label + "E"
//						String featureStr = "BigramFeature:\t" + BIAS_FEATURE;
//						makeFeature(featureStr, this.getFV(i), BigDecimal.ONE, i, new ArrayList<SignalInstance>(), addIfNotPresent, useIfNotPresent);
//					}
//					else { //genericLabel == Default_Trigger_Label
//						for (ScorerData scorerData : perceptron.triggerScorers) {
//							//String signalName = (String) signalNameObj;
//							List<SignalInstance> signals = new ArrayList<SignalInstance>();
//							//double numFalse = 0.0;
//							
//		//					/**
//		//					 * If at least one spec has a positive signal - then the value is FEATURE_NEGATIVE_VAL,
//		//					 * meaning that the token doesn't fit Default_Trigger_Label ("O").
//		//					 * 
//		//					 * Otherwise (no spec fits), the value is FEATURE_POSITIVE_VAL - the token fits "O". 
//		//					 */
//		//					BigDecimal featureValue = FEATURE_NEGATIVE_VAL;//FEATURE_POSITIVE_VAL;
//		//					for (Map<String, SignalInstance> signalsOfLabel : token.values()) {
//		//						SignalInstance signal = signalsOfLabel.get(signalName);
//		//						if (signal == null) {
//		//							throw new IllegalArgumentException(String.format("Cannot find feature '%s' for non-label token %d", signalName, i));
//		//						}
//		//						signals.add(signal);
//		//						if (signal.positive) {
//		//							featureValue = FEATURE_POSITIVE_VAL;//FEATURE_NEGATIVE_VAL;//0.0 //-1.0;
//		//							break;
//		//						}
//		//					}
//							
//							if (token.size() != 1) {
//								throw new IllegalStateException("token.size() should be 1 (1 non-O label, ATTACK), but it's " + token.size());
//							}
//							Map<ScorerData, SignalInstance> signalsOfAttack = token.values().iterator().next();
//							SignalInstance signalOfAttack = signalsOfAttack.get(scorerData);
//							signals.add(signalOfAttack);
//		
//							// Set feature value according to requested O Method
//							BigDecimal featureValue = null;
//							if (this.controller.oMethod.startsWith("A")) {
//								featureValue = signalOfAttack.positive ? BigDecimal.ZERO : BigDecimal.ONE;
//							}
//							else if (this.controller.oMethod.startsWith("B")) {
//								featureValue = signalOfAttack.positive ? BigDecimal.ONE : BigDecimal.ZERO;
//							}
//							else if (this.controller.oMethod.startsWith("C")) {
//								featureValue = BigDecimal.ZERO;
//							}
//							else if (this.controller.oMethod.startsWith("D")) {
//								featureValue = BigDecimal.ONE;
//							}
//							else {
//								throw new IllegalArgumentException("Illegal value for 'oMethod': '" + this.controller.oMethod + "'");
//							}
//							
//												
//							//String featureStr = "BigramFeature:\t" + signalName;
//							String featureStr = "BigramFeature:\t" + signalOfAttack.getName() + "\t" + CURRENT_LABEL_MARKER + genericLabel;
//							makeFeature(featureStr, this.getFV(i), featureValue, i, signals, addIfNotPresent, useIfNotPresent);
//						}
//					}
				}
			}
		}
		catch (Exception e) {
			throw new RuntimeException(String.format("Exception when building node features, i=%s, SentenceInstance=%s", i, problem), e);
		}
	}
	
	protected void makeFeature(String featureStr, FeatureVector fv, Integer i, Integer entityIndex, List<SignalInstance> signals,
			String signalName, String label, String moreParams,
			boolean add_if_not_present,	boolean use_if_not_present)
	{
		makeFeature(featureStr, fv, BigDecimal.ONE, i, entityIndex, signals, signalName, label, moreParams, add_if_not_present, use_if_not_present);
	}
	
	/**
	 * add a (possible) feature to feature vector 
	 * @param featureStr
	 * @param fv
	 * @param add_if_not_present true if the feature is not in featureAlphabet, add it
	 * @param use_if_not_present true if the feature is not in featureAlphaebt, still use it in FV
	 */
	protected void makeFeature(String featureStr, FeatureVector fv,
			BigDecimal value, Integer i, Integer k, List<SignalInstance> signals,
			String signalName, String label, String moreParams,
			boolean add_if_not_present,	boolean use_if_not_present)
	{
		// Feature feat = new Feature(null, featureStr);
		// lookup the feature table to create an assignment with the new feature
		boolean wasAdded = false;
		featureStr = featureStr.intern();
		if(!use_if_not_present || add_if_not_present)
		{
			int feat_index = lookupFeatures(this.featureAlphabet, featureStr, add_if_not_present);
			if(feat_index != -1)
			{
				fv.add(featureStr, value);
				wasAdded = true;
			}
		}
		else
		{
			fv.add(featureStr, value);
			wasAdded = true;
		}
		
		if (k != null && wasAdded) {
			fv.add(featureStr, value, k);
		}
		
		if (controller.saveSignalsToValues && wasAdded) {
			String strippedFeatureStr = stripLabel(featureStr);
			Map<String, String> map = signalsToValues.get(i);
			if (!signalsToValues.containsKey(i)) {
				map = new HashMap<String, String>();
				signalsToValues.put(i, map);
			}
			if (map.containsKey(strippedFeatureStr)) {
				//List<SignalInstance> existingSignals = map.get(strippedFeatureStr);
				String existingSignals = map.get(strippedFeatureStr);
				// TODO I have some feeling that this exception will rise whenever I switch back to multiple labels.
				// Perhaps this would mean that featureToSignal should be even more complex and also have a distinct "label" level.
				throw new IllegalArgumentException(String.format("Tried to store signals %s for feature (stripped) '%s' over i=%s, but this feature already exists over this i, it already has the signals %s",
						signals, strippedFeatureStr, i, existingSignals));
			}
			String signalStr;
			if (signals.size() == 1) {
				signalStr = signals.get(0).getPositiveString();
			}
			else {
				throw new RuntimeException("So, I didn't have the energy to implement this for multiple signals per feature, but now it's rising... So, please implement :)");
			}
			map.put(strippedFeatureStr, signalStr);
		}
		
		if (wasAdded) {
			String signalNameNormalized = Logs.feature(signalName).intern();
			Set<String> storedSignals = signalToFeature.keySet();
			if (storedSignals.contains(signalNameNormalized) || storedSignals.size()<SIGNALS_TO_STORE) {
				Map<String, Map<String, String>> forSignal = signalToFeature.get(signalNameNormalized);
				if (forSignal == null) {
					forSignal = new HashMap<String, Map<String, String>>();
					signalToFeature.put(signalNameNormalized, forSignal);
				}
				Map<String, String> forLabel = forSignal.get(label);
				if (forLabel == null) {
					forLabel = new HashMap<String, String>();
					forSignal.put(label, forLabel);
				}
				
				forLabel.put(moreParams, featureStr);
			}
		}
	}
	
	/**
	 * lookup the feature (new feature), and then add to alphabet / weights vector if needed
	 * @param assn
	 * @param feat
	 * @return
	 */
	protected static int lookupFeatures(Alphabet dict, Object feat, boolean add_if_not_present)
	{
		int feat_index = dict.lookupIndex(feat, add_if_not_present);
		
		if(feat_index == -1 && !add_if_not_present)
		{
			return -1;
		}
		
		return feat_index;
	}
	
	public void setScore(BigDecimal sc)
	{
		score = sc;
	}
	
	/**
	 * get the score according to feature and weight 
	 * @return
	 */
	public BigDecimal getScore()
	{
		return score;
	}
	
	/**
	 * assume a new state(token) is added during search, then calculate the score for this state, update the total score 
	 * and then, add it to the total score
	 */
	public void updateScoreForNewState(FeatureVector weights)
	{
		FeatureVector fv = this.getCurrentFV();
		BigDecimal partial_score = fv.dotProduct(weights);
		this.partial_scores.set(state, partial_score);
		
		this.score = BigDecimal.ZERO;
		for(int i=0; i<=state; i++)
		{
			this.score = this.score.add(this.partial_scores.get(i)); //this.score += this.partial_scores.get(i);
		}
	}

	/**
	 * check if two assignments equals to each other before (inclusive) state step
	 * @param target
	 * @param state2
	 */
	public boolean equals(SentenceAssignment assn, int step)
	{
		for(int i=0; i<=step && i<=assn.state && i<=this.state; i++)
		{
			if(!assn.nodeAssignment.get(i).equals(this.nodeAssignment.get(i)))
			{
				return false;
			}
			
			if (controller.useArguments) {
				if(assn.edgeAssignment.get(i) != null && !assn.edgeAssignment.get(i).equals(this.edgeAssignment.get(i)))
				{
					return false;
				}
				// Qi: added April 11th, 2013
				if(assn.edgeAssignment.get(i) == null && this.edgeAssignment.get(i) != null)
				{
					System.err.println("SentenceAssignment:" + 721);
					return false;
				}
			}
		}
		return true;
	}
	
	/**
	 * check if two assignments equals to each other 
	 * @param target
	 * @param state2
	 */
	@Override
	public boolean equals(Object obj)
	{
		if(!(obj instanceof SentenceAssignment))
		{
			return false;
		}
		SentenceAssignment assn = (SentenceAssignment) obj;
		if(this.state != assn.state)
		{
			return false;
		}
		for(int i=0; i<=assn.state && i<=this.state; i++)
		{
			if(!assn.nodeAssignment.get(i).equals(this.nodeAssignment.get(i)))
			{
				return false;
			}
			
			if (controller.useArguments) {
				if(assn.edgeAssignment.get(i) != null && !assn.edgeAssignment.get(i).equals(this.edgeAssignment.get(i)))
				{
					return false;
				}
			}
		}
		return true;
	}
	
	/**
	 * check if two assignments equals to each other before (inclusive) state step
	 * @param target
	 * @param state2
	 */
	public boolean equals(SentenceAssignment assn, int step, int argNum)
	{
		for(int i=0; i<step && i<=assn.state && i<=this.state; i++)
		{
			if(!assn.nodeAssignment.get(i).equals(this.nodeAssignment.get(i)))
			{
				return false;
			}
			
			if (controller.useArguments) {
				if(assn.edgeAssignment.get(i) != null && !assn.edgeAssignment.get(i).equals(this.edgeAssignment.get(i)))
				{
					return false;
				}
				// Qi: added April 11th, 2013
				if(assn.edgeAssignment.get(i) == null && this.edgeAssignment.get(i) != null)
				{
					System.err.println("SentenceAssignment:" + 779);
					return false;
				}
			}			
		}
		if(!assn.nodeAssignment.get(step).equals(this.nodeAssignment.get(step)))
		{
			return false;
		}
		if(argNum < 0)
		{
			// if argument num < 0, only consider the trigger labeling
			return true;
		}
		
		else if (controller.useArguments)
		{
			Map<Integer, Integer> map_assn = assn.edgeAssignment.get(step);
			Map<Integer, Integer> map = this.edgeAssignment.get(step);
			if(map_assn == null && map == null)
			{
				return true;
			}
			for(int k=0; k<=argNum; k++)
			{
				Integer label_assn = null;
				Integer label = null;
				if(map_assn != null)
				{
					label_assn = map_assn.get(k);
				}
				if(map != null)
				{
					label = map.get(k);
				}
				if(label == null && label_assn != null || label != null && label_assn == null)
				{
					return false;
				}
				if(label != null && label_assn != null && (!label.equals(label_assn)))
				{
					return false;
				}
			}
		}
		/////////
		
		return true;
	}
	
	public void setViolate(boolean violate)
	{
		this.violate = violate;
	}
	
	public boolean getViolate()
	{
		return this.violate;
	}
	
	@Override
	public String toString() {
		return toString(null);
	}
	
	public String toString(Integer maxNodes)
	{
		StringBuffer ret = new StringBuffer(String.format("%s/%s/%s: ", ord, sentInstId, state));
		int i=0;
		for(Integer assn : this.nodeAssignment)
		{
			if (maxNodes == null || i < maxNodes) {
				String token_label = (String) this.nodeTargetAlphabet.lookupObject(assn);
				ret.append(" ");
				ret.append(token_label);
				
				Map<Integer, Integer> edges = this.edgeAssignment.get(i);
				if(edges != null)
				{
					ret.append("(");
					for(Integer key : edges.keySet())
					{
						ret.append(" ");
						ret.append(key);
						ret.append(":");
						Integer val = edges.get(key);
						String arg_role = (String) this.edgeTargetAlphabet.lookupObject(val);
						ret.append(arg_role);
					}
					ret.append(")");
				}
				i++;
			}
			else {
				ret.append("+");
				break;
			}
		}
		return ret.toString();
	}
	
	/**
	 * check if the label of the token is an event, thereby can be attached to argument
	 * @param currentNodeLabel
	 * @return
	 */
	public static boolean isArgumentable(String label)
	{
		if(label.equalsIgnoreCase(PAD_Trigger_Label))
		{
			return false;
		}
		return true;
	}

	// to store temprary local score
	//protected double local_score = 0.0;
	
//	public void setLocalScore(double local_score)
//	{
//		this.local_score = local_score;
//	}
	
//	public double getLocalScore()
//	{
//		return local_score;
//	}

//	public void addLocalScore(double local_score)
//	{
//		this.local_score += local_score;
//	}
	
	/**
	 * clear feature vectors, so that the Target assignment can creates its feature vector in beamSearch
	 */
	public void clearFeatureVectors()
	{
		for(int i=0; i<this.featVecSequence.size(); i++)
		{
			this.featVecSequence.sequence.set(i, new FeatureVector());
		}
		signalsToValues = new HashMap<Integer, Map<String, String>>();
	}
}
