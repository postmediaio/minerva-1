package org.geneontology.minerva.server.handler;

import static org.geneontology.minerva.server.handler.OperationsTools.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.geneontology.minerva.ModelContainer;
import org.geneontology.minerva.MolecularModelManager;
import org.geneontology.minerva.UndoAwareMolecularModelManager;
import org.geneontology.minerva.CoreMolecularModelManager.DeleteInformation;
import org.geneontology.minerva.MolecularModelManager.UnknownIdentifierException;
import org.geneontology.minerva.UndoAwareMolecularModelManager.ChangeEvent;
import org.geneontology.minerva.UndoAwareMolecularModelManager.UndoMetadata;
import org.geneontology.minerva.curie.CurieHandler;
import org.geneontology.minerva.json.JsonAnnotation;
import org.geneontology.minerva.json.JsonEvidenceInfo;
import org.geneontology.minerva.json.JsonOwlObject;
import org.geneontology.minerva.json.JsonRelationInfo;
import org.geneontology.minerva.json.JsonTools;
import org.geneontology.minerva.json.MolecularModelJsonRenderer;
import org.geneontology.minerva.legacy.GafExportTool;
import org.geneontology.minerva.server.external.ExternalLookupService;
import org.geneontology.minerva.server.handler.M3BatchHandler.M3BatchResponse;
import org.geneontology.minerva.server.handler.M3BatchHandler.M3Request;
import org.geneontology.minerva.server.handler.M3BatchHandler.Operation;
import org.geneontology.minerva.server.handler.M3BatchHandler.M3BatchResponse.MetaResponse;
import org.geneontology.minerva.server.handler.M3BatchHandler.M3BatchResponse.ResponseData;
import org.geneontology.minerva.server.handler.OperationsTools.MissingParameterException;
import org.geneontology.minerva.server.validation.BeforeSaveModelValidator;
import org.geneontology.minerva.util.AnnotationShorthand;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLException;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

/**
 * Separate the actual calls to the {@link UndoAwareMolecularModelManager} from the
 * request, error and response handling.
 * 
 * @see JsonOrJsonpBatchHandler
 */
abstract class OperationsImpl {

	final UndoAwareMolecularModelManager m3;
	final Set<OWLObjectProperty> importantRelations;
	final BeforeSaveModelValidator beforeSaveValidator;
	final ExternalLookupService externalLookupService;
	final CurieHandler curieHandler;
	final Set<IRI> dataPropertyIRIs;
	final boolean useModuleReasoner;
	
	OperationsImpl(UndoAwareMolecularModelManager models, 
			Set<OWLObjectProperty> importantRelations,
			ExternalLookupService externalLookupService,
			boolean useModuleReasoner) {
		super();
		this.m3 = models;
		this.useModuleReasoner = useModuleReasoner;
		this.importantRelations = importantRelations;
		this.externalLookupService = externalLookupService;
		this.curieHandler = models.getCuriHandler();
		this.beforeSaveValidator = new BeforeSaveModelValidator();
		Set<IRI> dataPropertyIRIs = new HashSet<IRI>();
		for(OWLDataProperty p : m3.getOntology().getDataPropertiesInSignature(true)) {
			dataPropertyIRIs.add(p.getIRI());
		}
		this.dataPropertyIRIs = Collections.unmodifiableSet(dataPropertyIRIs);
	}

	abstract boolean enforceExternalValidate();

	abstract boolean checkLiteralIdentifiers();
	
	abstract boolean validateBeforeSave();
	
	abstract boolean useUserId();
	
	static class BatchHandlerValues {
		
		final Set<OWLNamedIndividual> relevantIndividuals = new HashSet<>();
		boolean renderBulk = false;
		boolean nonMeta = false;
		ModelContainer model = null;
		Map<String, OWLNamedIndividual> individualVariable = new HashMap<>();
		
		public boolean notVariable(String id) {
			return individualVariable.containsKey(id) == false;
		}
		
		public OWLNamedIndividual getVariableValue(String id) throws UnknownIdentifierException {
			if (individualVariable.containsKey(id)) {
				OWLNamedIndividual individual = individualVariable.get(id);
				if (individual == null) {
					throw new UnknownIdentifierException("Variable "+id+" has a null value.");
				}
				return individual;
			}
			return null;
		}
		
		public void addVariableValue(String id, OWLNamedIndividual i) throws UnknownIdentifierException {
			if (id != null) {
				individualVariable.put(id, i);	
			}
		}
	}
	

	private OWLNamedIndividual getIndividual(String id, BatchHandlerValues values) throws UnknownIdentifierException {
		if (values.notVariable(id)) {
			IRI iri = curieHandler.getIRI(id); 
			OWLNamedIndividual i = m3.getIndividual(iri, values.model);
			if (i == null) {
				throw new UnknownIdentifierException("No individual found for id: '"+id+"' and IRI: "+iri+" in model: "+values.model.getModelId());
			}
			return i;
		}
		return values.getVariableValue(id);
	}
	
	/**
	 * Handle the request for an operation regarding an individual.
	 * 
	 * @param request
	 * @param operation
	 * @param userId
	 * @param token
	 * @param values
	 * @return error or null
	 * @throws Exception 
	 */
	String handleRequestForIndividual(M3Request request, Operation operation, String userId, UndoMetadata token, BatchHandlerValues values) throws Exception {
		values.nonMeta = true;
		requireNotNull(request.arguments, "request.arguments");
		values.model = checkModelId(values.model, request);

		// get info, no modification
		if (Operation.get == operation) {
			requireNotNull(request.arguments.individual, "request.arguments.individual");
			OWLNamedIndividual i = getIndividual(request.arguments.individual, values);
			values.relevantIndividuals.add(i);
		}
		// create individual (look-up variable first) and add type
		else if (Operation.add == operation) {
			// required: expression
			// optional: more expressions, values
			requireNotNull(request.arguments.expressions, "request.arguments.expressions");
			Set<OWLAnnotation> annotations = extract(request.arguments.values, userId, values, values.model);
			Map<OWLDataProperty, Set<OWLLiteral>> dataProperties = extractDataProperties(request.arguments.values, values.model);
			OWLNamedIndividual individual;
			List<OWLClassExpression> clsExpressions = new ArrayList<OWLClassExpression>(request.arguments.expressions.length);
			for(JsonOwlObject expression : request.arguments.expressions) {
				OWLClassExpression cls = parseM3Expression(expression, values);
				clsExpressions.add(cls);
			}
			if (values.notVariable(request.arguments.individual)) {
				// create indivdual
				if (request.arguments.individualIRI != null) {
					IRI iri = curieHandler.getIRI(request.arguments.individualIRI);
					individual = m3.createIndividualNonReasoning(values.model, iri, annotations, token);
				}
				else {
					individual = m3.createIndividualNonReasoning(values.model, annotations, token);
				}

				// add to render list and set variable
				values.relevantIndividuals.add(individual);
				values.addVariableValue(request.arguments.assignToVariable, individual);
			}
			else {
				individual = values.getVariableValue(request.arguments.individual);
			}
			if (individual != null) {
				// add types
				for (OWLClassExpression clsExpression : clsExpressions) {
					m3.addTypeNonReasoning(values.model, individual, clsExpression, token);
				}
				
				if (dataProperties.isEmpty() == false) {
					m3.addDataProperties(values.model, individual, dataProperties, token);
				}
				updateDate(values.model, individual, token, m3);
			}
			updateModelAnnotations(values.model, userId, token, m3);
		}
		// remove individual (and all axioms using it)
		else if (Operation.remove == operation){
			// required: modelId, individual
			requireNotNull(request.arguments.individual, "request.arguments.individual");
			OWLNamedIndividual i = getIndividual(request.arguments.individual, values);
			
			DeleteInformation dInfo = m3.deleteIndividualNonReasoning(values.model, i, token);
			handleRemovedAnnotationIRIs(dInfo.usedIRIs, values.model, token);
			updateAnnotationsForDelete(dInfo, values.model, userId, token, m3);
			updateModelAnnotations(values.model, userId, token, m3);
			values.renderBulk = true;
		}				
		// add type / named class assertion
		else if (Operation.addType == operation){
			// required: individual, expressions
			requireNotNull(request.arguments.individual, "request.arguments.individual");
			requireNotNull(request.arguments.expressions, "request.arguments.expressions");
			
			Set<OWLAnnotation> annotations = createGeneratedAnnotations(values.model, userId);
			OWLNamedIndividual i = getIndividual(request.arguments.individual, values);
			
			for(JsonOwlObject expression : request.arguments.expressions) {
				OWLClassExpression cls = parseM3Expression(expression, values);
				m3.addTypeNonReasoning(values.model, i, cls, token);
				values.relevantIndividuals.add(i);
				values.addVariableValue(request.arguments.assignToVariable, i);
				m3.addAnnotations(values.model, i, annotations, token);
			}
			updateDate(values.model, i, token, m3);
			updateModelAnnotations(values.model, userId, token, m3);
		}
		// remove type / named class assertion
		else if (Operation.removeType == operation){
			// required: individual, expressions
			requireNotNull(request.arguments.individual, "request.arguments.individual");
			requireNotNull(request.arguments.expressions, "request.arguments.expressions");
			
			Set<OWLAnnotation> annotations = createGeneratedAnnotations(values.model, userId);
			OWLNamedIndividual i = getIndividual(request.arguments.individual, values);
			
			for(JsonOwlObject expression : request.arguments.expressions) {
				OWLClassExpression cls = parseM3Expression(expression, values);
				m3.removeTypeNonReasoning(values.model, i, cls, token);
				values.relevantIndividuals.add(i);
				values.addVariableValue(request.arguments.assignToVariable, i);
				m3.addAnnotations(values.model, i, annotations, token);
			}
			updateDate(values.model, i, token, m3);
			updateModelAnnotations(values.model, userId, token, m3);
		}
		// add annotation
		else if (Operation.addAnnotation == operation){
			// required: individual, values
			requireNotNull(request.arguments.individual, "request.arguments.individual");
			requireNotNull(request.arguments.values, "request.arguments.values");

			Set<OWLAnnotation> annotations = extract(request.arguments.values, userId, values, values.model);
			Map<OWLDataProperty, Set<OWLLiteral>> dataProperties = extractDataProperties(request.arguments.values, values.model);
			OWLNamedIndividual i = getIndividual(request.arguments.individual, values);
			
			values.relevantIndividuals.add(i);
			if (annotations.isEmpty() == false) {
				m3.addAnnotations(values.model, i, annotations, token);
			}
			if (dataProperties.isEmpty() == false) {
				m3.addDataProperties(values.model, i, dataProperties, token);
			}
			values.addVariableValue(request.arguments.assignToVariable, i);
			updateDate(values.model, i, token, m3);
			updateModelAnnotations(values.model, userId, token, m3);
		}
		// remove annotation
		else if (Operation.removeAnnotation == operation){
			// required: individual, values
			requireNotNull(request.arguments.individual, "request.arguments.individual");
			requireNotNull(request.arguments.values, "request.arguments.values");

			Set<OWLAnnotation> annotations = extract(request.arguments.values, null, values, values.model);
			Map<OWLDataProperty, Set<OWLLiteral>> dataProperties = extractDataProperties(request.arguments.values, values.model);
			OWLNamedIndividual i = getIndividual(request.arguments.individual, values);
			
			Set<IRI> usedIRIs = MolecularModelManager.extractIRIValues(annotations);
			
			values.relevantIndividuals.add(i);
			if (annotations.isEmpty() == false) {
				m3.removeAnnotations(values.model, i, annotations, token);
				
			}
			if (dataProperties.isEmpty() == false) {
				m3.removeDataProperties(values.model, i, dataProperties, token);
			}
			values.addVariableValue(request.arguments.assignToVariable, i);
			
			handleRemovedAnnotationIRIs(usedIRIs, values.model, token);
			updateDate(values.model, i, token, m3);
			updateModelAnnotations(values.model, userId, token, m3);
		}
		else {
			return "Unknown operation: "+operation;
		}
		return null;
	}
	
	private void handleRemovedAnnotationIRIs(Set<IRI> iriSets, ModelContainer model, UndoMetadata token) {
		if (iriSets != null) {
			for (IRI iri : iriSets) {
				OWLNamedIndividual i = m3.getIndividual(iri, model);
				if (i != null) {
					m3.deleteIndividual(model, i, token);
				}
				// ignoring undefined IRIs
			}
		}
	}

	private OWLClassExpression parseM3Expression(JsonOwlObject expression, BatchHandlerValues values)
			throws MissingParameterException, UnknownIdentifierException, OWLException {
		M3ExpressionParser p = new M3ExpressionParser(checkLiteralIdentifiers());
		if (enforceExternalValidate()) {
			return p.parse(values.model, expression, externalLookupService);
		}
		else {
			return p.parse(values.model, expression, null);
		}
	}
	
	private OWLObjectProperty getProperty(String id, BatchHandlerValues values) throws UnknownIdentifierException {
		OWLObjectProperty p = m3.getObjectProperty(id, values.model);
		if (p == null) {
			throw new UnknownIdentifierException("Could not find a property for id: "+id);
		}
		return p;
	}
	
	/**
	 * Handle the request for an operation regarding an edge.
	 * 
	 * @param request
	 * @param operation
	 * @param userId
	 * @param token
	 * @param values
	 * @return error or null
	 * @throws Exception
	 */
	String handleRequestForEdge(M3Request request, Operation operation, String userId, UndoMetadata token, BatchHandlerValues values) throws Exception {
		values.nonMeta = true;
		requireNotNull(request.arguments, "request.arguments");
		values.model = checkModelId(values.model, request);
		// required: subject, predicate, object
		requireNotNull(request.arguments.subject, "request.arguments.subject");
		requireNotNull(request.arguments.predicate, "request.arguments.predicate");
		requireNotNull(request.arguments.object, "request.arguments.object");
		// check for variables
		final OWLNamedIndividual s = getIndividual(request.arguments.subject, values);
		final OWLNamedIndividual o = getIndividual(request.arguments.object, values);
		final OWLObjectProperty p = getProperty(request.arguments.predicate, values);
		values.relevantIndividuals.addAll(Arrays.asList(s, o));
		
		// add edge
		if (Operation.add == operation){
			// optional: values
			Set<OWLAnnotation> annotations = extract(request.arguments.values, userId, values, values.model);
			addDateAnnotation(annotations, values.model.getOWLDataFactory());
			m3.addFactNonReasoning(values.model, p, s, o, annotations, token);
			updateModelAnnotations(values.model, userId, token, m3);
		}
		// remove edge
		else if (Operation.remove == operation){
			Set<IRI> removedIRIs = m3.removeFactNonReasoning(values.model, p, s, o, token);
			if (removedIRIs != null && removedIRIs.isEmpty() == false) {
				// only render bulk, iff there were additional deletes (i.e. evidence removal)
				values.renderBulk = true;
				handleRemovedAnnotationIRIs(removedIRIs, values.model, token);
			}
			updateModelAnnotations(values.model, userId, token, m3);
		}
		// add annotation
		else if (Operation.addAnnotation == operation){
			requireNotNull(request.arguments.values, "request.arguments.values");

			m3.addAnnotations(values.model, p, s, o,
					extract(request.arguments.values, userId, values, values.model), token);
			updateDate(values.model, p, s, o, token, m3);
			updateModelAnnotations(values.model, userId, token, m3);
		}
		// remove annotation
		else if (Operation.removeAnnotation == operation){
			requireNotNull(request.arguments.values, "request.arguments.values");

			Set<OWLAnnotation> annotations = extract(request.arguments.values, null, values, values.model);
			Set<IRI> iriSet = MolecularModelManager.extractIRIValues(annotations);
			m3.removeAnnotations(values.model, p, s, o, annotations, token);
			handleRemovedAnnotationIRIs(iriSet, values.model, token);
			updateDate(values.model, p, s, o, token, m3);
			updateModelAnnotations(values.model, userId, token, m3);
		}
		else {
			return "Unknown operation: "+operation;
		}
		return null;
	}
	
	/**
	 * Handle the request for an operation regarding a model.
	 * 
	 * @param request
	 * @param response
	 * @param operation
	 * @param userId
	 * @param token
	 * @param values
	 * @return error or null
	 * @throws Exception
	 */
	String handleRequestForModel(M3Request request, M3BatchResponse response, Operation operation, String userId, UndoMetadata token, BatchHandlerValues values) throws Exception {
		// get model
		if (Operation.get == operation){
			values.nonMeta = true;
			requireNotNull(request.arguments, "request.arguments");
			values.model = checkModelId(values.model, request);
			values.renderBulk = true;
		}
		else if (Operation.updateImports == operation){
			values.nonMeta = true;
			requireNotNull(request.arguments, "request.arguments");
			values.model = checkModelId(values.model, request);
			m3.updateImports(values.model);
			values.renderBulk = true;
		}
		// add an empty model
		else if (Operation.add == operation) {
			values.nonMeta = true;
			values.renderBulk = true;
			
			Set<OWLAnnotation> annotations = null;
//			if (request.arguments != null && request.arguments.taxonId != null) {
//				values.modelId = m3.generateBlankModelWithTaxon(request.arguments.taxonId, token);
//			}
//			else {
				values.model = m3.generateBlankModel(token);
//			}
			
			if (request.arguments != null) {
				annotations = extract(request.arguments.values, userId, values, values.model);
			}
			else {
				annotations = extract(null, userId, values, values.model);
			}
			if (annotations != null) {
				m3.addModelAnnotations(values.model, annotations, token);
			}
			updateModelAnnotations(values.model, userId, token, m3);
		}
		else if (Operation.addAnnotation == operation) {
			values.nonMeta = true;
			requireNotNull(request.arguments, "request.arguments");
			requireNotNull(request.arguments.values, "request.arguments.values");
			values.model = checkModelId(values.model, request);
			Set<OWLAnnotation> annotations = extract(request.arguments.values, userId, values, values.model);
			if (annotations != null) {
				m3.addModelAnnotations(values.model, annotations, token);
			}
			updateModelAnnotations(values.model, userId, token, m3);
		}
		else if (Operation.removeAnnotation == operation) {
			values.nonMeta = true;
			requireNotNull(request.arguments, "request.arguments");
			requireNotNull(request.arguments.values, "request.arguments.values");
			values.model = checkModelId(values.model, request);
			Set<OWLAnnotation> annotations = extract(request.arguments.values, null, values, values.model);
			if (annotations != null) {
				m3.removeAnnotations(values.model, annotations, token);
			}
			updateModelAnnotations(values.model, userId, token, m3);
			values.renderBulk = true;
		}
		else if (Operation.exportModel == operation) {
			if (values.nonMeta) {
				// can only be used with other "meta" operations in batch mode, otherwise it would lead to conflicts in the returned signal
				return "Export model can only be combined with other meta operations.";
			}
			requireNotNull(request.arguments, "request.arguments");
			values.model = checkModelId(values.model, request);
			export(response, values.model, userId);
		}
		else if (Operation.exportModelLegacy == operation) {
			if (values.nonMeta) {
				// can only be used with other "meta" operations in batch mode, otherwise it would lead to conflicts in the returned signal
				return "Export legacy model can only be combined with other meta operations.";
			}
			requireNotNull(request.arguments, "request.arguments");
			values.model = checkModelId(values.model, request);
			exportLegacy(response, values.model, request.arguments.format, userId);
		}
		else if (Operation.importModel == operation) {
			values.nonMeta = true;
			requireNotNull(request.arguments, "request.arguments");
			requireNotNull(request.arguments.importModel, "request.arguments.importModel");
			values.model = m3.importModel(request.arguments.importModel);
			
			Set<OWLAnnotation> annotations = extract(request.arguments.values, userId, values, values.model);
			if (annotations != null) {
				m3.addModelAnnotations(values.model, annotations, token);
			}
			updateModelAnnotations(values.model, userId, token, m3);
			values.renderBulk = true;
		}
		else if (Operation.storeModel == operation) {
			values.nonMeta = true;
			requireNotNull(request.arguments, "request.arguments");
			values.model = checkModelId(values.model, request);
			Set<OWLAnnotation> annotations = extract(request.arguments.values, userId, values, values.model);
			if (validateBeforeSave()) {
				List<String> issues = beforeSaveValidator.validateBeforeSave(values.model, useModuleReasoner);
				if (issues != null && !issues.isEmpty()) {
					StringBuilder commentary = new StringBuilder();
					for (Iterator<String> it = issues.iterator(); it.hasNext();) {
						String issue = it.next();
						commentary.append(issue);
						if (it.hasNext()) {
							commentary.append('\n');
						}
					}
					response.commentary = commentary.toString();
					return "Save model failed: validation error(s) before save";
				}
			}
			m3.saveModel(values.model, annotations, token);
			values.renderBulk = true;
		}
		else if (Operation.undo == operation) {
			values.nonMeta = true;
			requireNotNull(request.arguments, "request.arguments");
			values.model = checkModelId(values.model, request);
			m3.undo(values.model, userId);
			values.renderBulk = true;
		}
		else if (Operation.redo == operation) {
			values.nonMeta = true;
			requireNotNull(request.arguments, "request.arguments");
			values.model = checkModelId(values.model, request);
			m3.redo(values.model, userId);
			values.renderBulk = true;
		}
		else if (Operation.getUndoRedo == operation) {
			if (values.nonMeta) {
				// can only be used with other "meta" operations in batch mode, otherwise it would lead to conflicts in the returned signal
				return operation+" cannot be combined with other operations.";
			}
			requireNotNull(request.arguments, "request.arguments");
			values.model = checkModelId(values.model, request);
			getCurrentUndoRedoForModel(response, values.model.getModelId(), userId);
		}
		else {
			return "Unknown operation: "+operation;
		}
		return null;
	}

	private void getCurrentUndoRedoForModel(M3BatchResponse response, String modelId, String userId) {
		Pair<List<ChangeEvent>,List<ChangeEvent>> undoRedoEvents = m3.getUndoRedoEvents(modelId);
		initMetaResponse(response);
		List<Map<Object, Object>> undos = new ArrayList<Map<Object,Object>>();
		List<Map<Object, Object>> redos = new ArrayList<Map<Object,Object>>();
		final long currentTime = System.currentTimeMillis();
		for(ChangeEvent undo : undoRedoEvents.getLeft()) {
			Map<Object, Object> data = new HashMap<Object, Object>(3);
			data.put("user-id", undo.getUserId());
			data.put("time", Long.valueOf(currentTime-undo.getTime()));
			// TODO add a summary of the change? axiom count?
			undos.add(data);
		}
		for(ChangeEvent redo : undoRedoEvents.getRight()) {
			Map<Object, Object> data = new HashMap<Object, Object>(3);
			data.put("user-id", redo.getUserId());
			data.put("time", Long.valueOf(currentTime-redo.getTime()));
			// TODO add a summary of the change? axiom count?
			redos.add(data);
		}
		response.data.undo = undos;
		response.data.redo = redos;
	}
	
	private void initMetaResponse(M3BatchResponse response) {
		if (response.data == null) {
			response.data = new ResponseData();
			response.messageType = M3BatchResponse.MESSAGE_TYPE_SUCCESS;
			response.message = "success: 0";
			response.signal = M3BatchResponse.SIGNAL_META;
		}
	}
	
	/**
	 * Handle the request for the meta properties.
	 * 
	 * @param response
	 * @param userId
	 * @throws IOException 
	 * @throws OWLException 
	 */
	void getMeta(M3BatchResponse response, String userId) throws IOException, OWLException {
		// init
		initMetaResponse(response);
		if (response.data.meta == null) {
			response.data.meta = new MetaResponse();
		}
		
		// relations
		Pair<List<JsonRelationInfo>, List<JsonRelationInfo>> propPair = MolecularModelJsonRenderer.renderProperties(m3, importantRelations, curieHandler);
		final List<JsonRelationInfo> relList = propPair.getLeft();
		if (relList != null) {
			response.data.meta.relations = relList.toArray(new JsonRelationInfo[relList.size()]);
		}
		
		// data properties
		final List<JsonRelationInfo> propList = propPair.getRight();
		if (propList != null) {
			response.data.meta.dataProperties = propList.toArray(new JsonRelationInfo[propList.size()]);
		}
		
		// evidence
		final List<JsonEvidenceInfo> evidencesList = MolecularModelJsonRenderer.renderEvidences(m3, curieHandler);
		if (evidencesList != null) {
			response.data.meta.evidence = evidencesList.toArray(new JsonEvidenceInfo[evidencesList.size()]);	
		}
		
		// model ids
		final Map<String, String> allModelIds = m3.getAvailableModelIds();
		response.data.meta.modelIds = allModelIds.values(); // short form model ids
		
		Map<String,Map<String,String>> retMap = new HashMap<String, Map<String,String>>();
		
		// get model annotations
		for( Entry<String, String> entry : allModelIds.entrySet() ){

			retMap.put(entry.getValue(), new HashMap<String,String>());
			Map<String, String> modelMap = retMap.get(entry.getValue());
			
			// Iterate through the model's a.
			OWLOntology o = m3.getModelAbox(entry.getKey());
			Set<OWLAnnotation> annotations = o.getAnnotations();
			for( OWLAnnotation an : annotations ){
				Pair<String,String> pair = JsonTools.createSimplePair(an, curieHandler);
				if (pair != null) {
					modelMap.put(pair.getKey(), pair.getValue());
				}
			}
		}
		response.data.meta.modelsMeta = retMap;
	}
	
	
	private void export(M3BatchResponse response, ModelContainer model, String userId) throws OWLOntologyStorageException, UnknownIdentifierException {
		String exportModel = m3.exportModel(model);
		initMetaResponse(response);
		response.data.exportModel = exportModel;
	}
	
	private void exportLegacy(M3BatchResponse response, ModelContainer model, String format, String userId) throws IOException, OWLOntologyCreationException {
		final GafExportTool exportTool = GafExportTool.getInstance();
		String exportModel = exportTool.exportModelLegacy(model, useModuleReasoner, format);
		initMetaResponse(response);
		response.data.exportModel = exportModel;
	}
	
	private static OWLAnnotation create(OWLDataFactory f, AnnotationShorthand s, String literal) {
		return create(f, s, f.getOWLLiteral(literal));
	}
	
	private static OWLAnnotation create(OWLDataFactory f, AnnotationShorthand s, OWLAnnotationValue v) {
		final OWLAnnotationProperty p = f.getOWLAnnotationProperty(s.getAnnotationProperty());
		return f.getOWLAnnotation(p, v);
	}


	/**
	 * @param model
	 * @param request
	 * @return modelId
	 * @throws MissingParameterException
	 * @throws MultipleModelIdsParameterException
	 * @throws UnknownIdentifierException 
	 */
	public ModelContainer checkModelId(ModelContainer model, M3Request request) 
			throws MissingParameterException, MultipleModelIdsParameterException, UnknownIdentifierException {
		
		if (model == null) {
			final String currentModelId = request.arguments.modelId;
			requireNotNull(currentModelId, "request.arguments.modelId");
			model = m3.checkModelId(currentModelId);
		}
		else {
			final String currentModelId = request.arguments.modelId;
			if (currentModelId != null && model.getModelId().equals(currentModelId) == false) {
				throw new MultipleModelIdsParameterException("Using multiple modelIds in one batch call is not supported.");
			}
		}
		return model;
	}
	
	private Set<OWLAnnotation> extract(JsonAnnotation[] values, String userId, BatchHandlerValues batchValues, ModelContainer model) throws UnknownIdentifierException {
		Set<OWLAnnotation> result = new HashSet<OWLAnnotation>();
		OWLDataFactory f = model.getOWLDataFactory();
		if (values != null) {
			for (JsonAnnotation jsonAnn : values) {
				if (jsonAnn.key != null && jsonAnn.value != null) {
					AnnotationShorthand shorthand = AnnotationShorthand.getShorthand(jsonAnn.key);
					if (shorthand != null) {
						if (AnnotationShorthand.evidence == shorthand) {
							IRI evidenceIRI;
							if (batchValues.notVariable(jsonAnn.value)) {
								evidenceIRI = curieHandler.getIRI(jsonAnn.value);
							}
							else {
								evidenceIRI = batchValues.getVariableValue(jsonAnn.value).getIRI();
							}
							result.add(create(f, shorthand, evidenceIRI));
						}
						else {
							result.add(create(f, shorthand, JsonTools.createAnnotationValue(jsonAnn, f)));
						}
					}
					else {
						IRI pIRI = curieHandler.getIRI(jsonAnn.key);
						if (dataPropertyIRIs.contains(pIRI) == false) {
							OWLAnnotationValue annotationValue = JsonTools.createAnnotationValue(jsonAnn, f);
							result.add(f.getOWLAnnotation(f.getOWLAnnotationProperty(pIRI), annotationValue));
						}
					}
				}
			}
		}
		addGeneratedAnnotations(userId, result, f);
		return result;
	}
	
	private Map<OWLDataProperty, Set<OWLLiteral>> extractDataProperties(JsonAnnotation[] values, ModelContainer model) {
		Map<OWLDataProperty, Set<OWLLiteral>> result = new HashMap<OWLDataProperty, Set<OWLLiteral>>();
		
		if (values != null && values.length > 0) {
			OWLDataFactory f = model.getOWLDataFactory();
			for (JsonAnnotation jsonAnn : values) {
				if (jsonAnn.key != null && jsonAnn.value != null) {
					AnnotationShorthand shorthand = AnnotationShorthand.getShorthand(jsonAnn.key);
					if (shorthand == null) {
						IRI pIRI = curieHandler.getIRI(jsonAnn.key);
						if (dataPropertyIRIs.contains(pIRI)) {
							OWLLiteral literal = JsonTools.createLiteral(jsonAnn, f);
							if (literal != null) {
								OWLDataProperty property = f.getOWLDataProperty(pIRI);
								Set<OWLLiteral> literals = result.get(property);
								if (literals == null) {
									literals = new HashSet<OWLLiteral>();
									result.put(property, literals);
								}
								literals.add(literal);
							}
						}
					}
				}
			}
		}
		
		return result;
	}
	
	private void addGeneratedAnnotations(String userId, Set<OWLAnnotation> annotations, OWLDataFactory f) {
		if (useUserId() && userId != null) {
			annotations.add(create(f, AnnotationShorthand.contributor, userId));
		}
	}
	
	private void addDateAnnotation(Set<OWLAnnotation> annotations, OWLDataFactory f) {
		annotations.add(createDateAnnotation(f));
	}
	
	private OWLAnnotation createDateAnnotation(OWLDataFactory f) {
		return create(f, AnnotationShorthand.date, generateDateString());
	}

	private void updateDate(ModelContainer model, OWLNamedIndividual individual, UndoMetadata token, UndoAwareMolecularModelManager m3) throws UnknownIdentifierException {
		final OWLDataFactory f = model.getOWLDataFactory();
		m3.updateAnnotation(model, individual, createDateAnnotation(f), token);
	}
	
	private void updateAnnotationsForDelete(DeleteInformation info, ModelContainer model, String userId, UndoMetadata token, UndoAwareMolecularModelManager m3) throws UnknownIdentifierException {
		final OWLDataFactory f = model.getOWLDataFactory();
		final OWLAnnotation annotation = createDateAnnotation(f);
		final Set<OWLAnnotation> generated = new HashSet<OWLAnnotation>();
		addGeneratedAnnotations(userId, generated, f);
		for(IRI subject : info.touched) {
			m3.updateAnnotation(model, subject, annotation, token);
			m3.addAnnotations(model, subject, generated, token);
		}
		if (info.updated.isEmpty() == false) {
			Set<OWLObjectPropertyAssertionAxiom> newAxioms = 
					m3.updateAnnotation(model, info.updated, annotation, token);
			m3.addAnnotations(model, newAxioms, generated, token);
		}
	}
	
	private void updateDate(ModelContainer model, OWLObjectProperty predicate, OWLNamedIndividual subject, OWLNamedIndividual object, UndoMetadata token, UndoAwareMolecularModelManager m3) throws UnknownIdentifierException {
		final OWLDataFactory f = model.getOWLDataFactory();
		m3.updateAnnotation(model, predicate, subject, object, createDateAnnotation(f), token);
	}
	
	private void updateModelAnnotations(ModelContainer model, String userId, UndoMetadata token, MolecularModelManager<UndoMetadata> m3) throws UnknownIdentifierException {
		final OWLDataFactory f = model.getOWLDataFactory();
		if (useUserId() && userId != null) {
			Set<OWLAnnotation> annotations = new HashSet<OWLAnnotation>();
			annotations.add(create(f, AnnotationShorthand.contributor, userId));
			m3.addModelAnnotations(model, annotations, token);
		}
		m3.updateAnnotation(model, createDateAnnotation(f), token);
	}

	/**
	 * separate method, intended to be overridden during test.
	 * 
	 * @return date string, never null
	 */
	protected String generateDateString() {
		String dateString = MolecularModelJsonRenderer.AnnotationTypeDateFormat.get().format(new Date());
		return dateString;
	}
	
	private Set<OWLAnnotation> createGeneratedAnnotations(ModelContainer model, String userId) {
		Set<OWLAnnotation> annotations = new HashSet<OWLAnnotation>();
		OWLDataFactory f = model.getOWLDataFactory();
		addGeneratedAnnotations(userId, annotations, f);
		return annotations;
	}
	
	static class MultipleModelIdsParameterException extends Exception {

		private static final long serialVersionUID = 4362299465121954598L;

		/**
		 * @param message
		 */
		MultipleModelIdsParameterException(String message) {
			super(message);
		}
		
	}
}
