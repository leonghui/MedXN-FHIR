/*******************************************************************************
 * Copyright: (c)  2013  Mayo Foundation for Medical Education and
 * Research (MFMER). All rights reserved. MAYO, MAYO CLINIC, and the
 * triple-shield Mayo logo are trademarks and service marks of MFMER.
 *
 * Except as contained in the copyright notice above, or as used to identify
 * MFMER as the author of this software, the trade names, trademarks, service
 * marks, or product names of the copyright holder shall not be used in
 * advertising, promotion or otherwise in connection with this software without
 * prior written authorization of the copyright holder.
 *
 * Copyright (c) 2018-2019. Leong Hui Wong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.ohnlp.medxn.ae;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.FSArray;
import org.apache.uima.jcas.cas.TOP;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Level;
import org.hl7.fhir.dstu3.model.Medication;
import org.ohnlp.medtagger.type.ConceptMention;
import org.ohnlp.medxn.fhir.FhirQueryClient;
import org.ohnlp.medxn.fhir.FhirQueryUtils;
import org.ohnlp.medxn.type.Drug;
import org.ohnlp.medxn.type.Ingredient;
import org.ohnlp.medxn.type.LookupWindow;
import org.ohnlp.medxn.type.MedAttr;
import org.ohnlp.typesystem.type.textspan.Sentence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class FhirMedExtAnnotator extends JCasAnnotator_ImplBase {

    private FhirQueryClient queryClient;

    private ImmutableList<ConceptMention> concepts;
    private ImmutableList<MedAttr> attributes;
    private ImmutableList<Ingredient> ingredients;
    private ImmutableList<Sentence> sortedSentences;
    private ImmutableList<MedAttr> formsRoutesFrequencies;
    private List<Medication> allMedications;

    @Override
    public void initialize(UimaContext uimaContext) throws ResourceInitializationException {
        super.initialize(uimaContext);

        // Get config parameter values
        String url = (String) uimaContext.getConfigParameterValue("FHIR_SERVER_URL");
        int timeout = (int) uimaContext.getConfigParameterValue("TIMEOUT_SEC");
        queryClient = FhirQueryClient.createFhirQueryClient(url, timeout);

        allMedications = queryClient.getAllMedications();
    }

    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void process(JCas jcas) {
        concepts = ImmutableList.copyOf(jcas.getAnnotationIndex(ConceptMention.type));
        attributes = ImmutableList.copyOf(jcas.getAnnotationIndex(MedAttr.type));
        ingredients = ImmutableList.copyOf(jcas.getAnnotationIndex(Ingredient.type));
        sortedSentences = ImmutableList.sortedCopyOf(
                Comparator.comparingInt(Sentence::getBegin).thenComparingInt(Sentence::getEnd),
                jcas.getAnnotationIndex(Sentence.type)
        );
        formsRoutesFrequencies = attributes.stream()
                .filter(attribute -> attribute.getTag().contentEquals(FhirQueryUtils.MedAttrConstants.FORM)
                        ||
                        attribute.getTag().contentEquals(FhirQueryUtils.MedAttrConstants.ROUTE)
                        ||
                        attribute.getTag().equals(FhirQueryUtils.MedAttrConstants.FREQUENCY))
                .collect(ImmutableList.toImmutableList());

        convertConceptMentions(jcas);
        mergeBrandAndGenerics(jcas);
        createLookupWindows(jcas);
        associateAttributesAndIngredients(jcas);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void convertConceptMentions(JCas jcas) {
        List<Drug> drugs = new ArrayList<>();

        // SCENARIO 1: ingredients are ordered with form or route or frequency towards the end (terminator)
        SetMultimap<MedAttr, ConceptMention> conceptsByClosestTerminator = LinkedHashMultimap.create();

        concepts.forEach(concept -> formsRoutesFrequencies.stream()
                .filter(attribute ->
                        attribute.getBegin() > concept.getEnd())
                .min(Comparator.comparingInt(MedAttr::getBegin))
                .ifPresent(closestAttribute ->
                        conceptsByClosestTerminator.put(closestAttribute, concept)
                ));

        // SCENARIO 1.1: prioritize the ingredient closest to the terminator
        conceptsByClosestTerminator.asMap().forEach((key, value) -> value.stream()
                .max(Comparator.comparingInt(ConceptMention::getBegin))
                .ifPresent(concept -> {
                            Drug drug = new Drug(jcas, concept.getBegin(), concept.getEnd());
                            drugs.add(drug);
                        }
                ));

        // SCENARIO 2: ingredients ordered without terminators are standalone drugs
        IntStream.range(0, concepts.size()).forEach(index -> {
            ConceptMention concept = concepts.get(index);

            if (index < concepts.size() - 1) {
                ConceptMention nextConcept = concepts.get(index + 1);

                boolean terminatorFound = formsRoutesFrequencies.stream().anyMatch(attribute ->
                        attribute.getBegin() > concept.getEnd() &&
                                attribute.getEnd() < nextConcept.getBegin());

                if (!terminatorFound) {
                    Drug drug = new Drug(jcas, concept.getBegin(), concept.getEnd());
                    drugs.add(drug);
                }
            }
        });

        drugs.forEach(drug -> {
           ImmutableList<Ingredient> overlappingIngredients = ingredients.stream()
           .filter(ingredient ->
                   ingredient.getBegin() >= drug.getBegin() &&
                   ingredient.getEnd() <= drug.getEnd())
                   .collect(ImmutableList.toImmutableList());

           if (overlappingIngredients.size() > 0) {
               FSArray ingredients = new FSArray(jcas, overlappingIngredients.size());

               ingredients.copyFromArray(
                       overlappingIngredients.toArray(
                               new Ingredient[0]), 0,0, overlappingIngredients.size());

               drug.setIngredients(ingredients);
           }
        });

        drugs.forEach(TOP::addToIndexes);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void mergeBrandAndGenerics(JCas jcas) {
        // always generate a new instance of drug list because the index has been updated
        ImmutableList<Drug> sortedDrugs = ImmutableList.sortedCopyOf(
                Comparator.comparingInt(Drug::getBegin).thenComparingInt(Drug::getEnd),
                jcas.getAnnotationIndex(Drug.type)
        );

        // SCENARIO 1: Brand name is followed by ingredient name
        IntStream.range(0, sortedDrugs.size()).forEach(drugIndex -> {
            Drug drug = sortedDrugs.get(drugIndex);

            if (drug.getBrand() != null) {
                Drug nextDrug = sortedDrugs.get(drugIndex + 1);

                if (nextDrug.getBrand() == null) {
                    ImmutableList<String> rxCuis = ImmutableList.copyOf(drug.getBrand().split(","));

                    Set<Medication> brandedMedications = FhirQueryUtils.getMedicationsFromRxCui(allMedications, rxCuis);

                    ImmutableList<String> productIngredients = ImmutableList
                            .copyOf(FhirQueryUtils.getIngredientsFromMedications(brandedMedications));

                    if (nextDrug.getIngredients() != null) {
                        ImmutableList<String> genericIngredients = ImmutableList
                                .copyOf(nextDrug.getIngredients())
                                .stream()
                                .map(featureStructure -> (Ingredient) featureStructure)
                                .map(Ingredient::getItem)
                                .collect(ImmutableList.toImmutableList());

                        if (productIngredients.containsAll(genericIngredients)) {
                            mergeDrugs(jcas, nextDrug, drug);

                            getContext().getLogger().log(Level.INFO, "Merged drug: " +
                                    nextDrug.getCoveredText() +
                                    " with drug: " + drug.getCoveredText()
                            );
                        }
                    }
                }
            }
        });
    }

    private void createLookupWindows(JCas jcas) {
        // always generate a new instance of drug list because the index has been updated
        ImmutableList<Drug> sortedDrugs = ImmutableList.sortedCopyOf(
                Comparator.comparingInt(Drug::getBegin).thenComparingInt(Drug::getEnd),
                jcas.getAnnotationIndex(Drug.type)
        );

        List<LookupWindow> windows = new ArrayList<>();

        IntStream.range(0, sortedSentences.size()).forEach(sentenceIndex -> {
            Sentence sentence = sortedSentences.get(sentenceIndex);

            IntStream.range(0, sortedDrugs.size()).forEach(drugIndex -> {

                Drug drug = sortedDrugs.get(drugIndex);

                // only consider drugs that begin, but not necessarily end, in the same sentence
                if (drug.getBegin() >= sentence.getBegin() && drug.getBegin() <= sentence.getEnd()) {

                    LookupWindow window = new LookupWindow(jcas);

                    window.setBegin(drug.getBegin());

                    int nextDrugBegin = drugIndex < sortedDrugs.size() - 1 ?
                            sortedDrugs.get(drugIndex + 1).getBegin() : Integer.MAX_VALUE;

                    int nextSentenceEnd = sentenceIndex < sortedSentences.size() - 1 ?
                            sortedSentences.get(sentenceIndex + 1).getEnd() : sentence.getEnd();

                    int windowEnd = nextDrugBegin < nextSentenceEnd ? nextDrugBegin : nextSentenceEnd;

                    window.setEnd(windowEnd);

                    windows.add(window);
                }
            });
        });
        windows.forEach(TOP::addToIndexes);
    }

    private void associateAttributesAndIngredients(JCas jcas) {
        jcas.getAnnotationIndex(LookupWindow.type).forEach(window ->
                        jcas.getAnnotationIndex(Drug.type).subiterator(window).forEachRemaining(annotation -> {

                            Drug drug = (Drug) annotation;

                            // attributes are assumed not to be contained in drug names
                            List<MedAttr> filteredAttributes = attributes.stream()
                                    .filter(attribute ->
                                            attribute.getBegin() >= window.getBegin() &&
                                                    attribute.getEnd() <= window.getEnd())
                                    .collect(Collectors.toList());

                            if (filteredAttributes.size() > 0) {
                                FSArray attributesArray = new FSArray(jcas, filteredAttributes.size());

                                IntStream.range(0, filteredAttributes.size())
                                        .forEach(index ->
                                                attributesArray.set(index, filteredAttributes.get(index))
                                        );

                                drug.setAttrs(attributesArray);

                                getContext().getLogger().log(Level.INFO, "Associating attributes: " +
                                        FhirQueryUtils.getCoveredTextFromAnnotations(filteredAttributes) +
                                        " with drug: " + drug.getCoveredText()
                                );
                            }
                        })
        );
    }

    @SuppressWarnings("UnstableApiUsage")
    private void mergeDrugs(JCas jcas, Drug sourceDrug, Drug targetDrug) {

        // expand interval to cover both drugs
        int begin = sourceDrug.getBegin() < targetDrug.getBegin() ? sourceDrug.getBegin() : targetDrug.getBegin();
        int end = sourceDrug.getEnd() > targetDrug.getEnd() ? sourceDrug.getEnd() : targetDrug.getEnd();

        Drug mergedDrug = new Drug(jcas, begin, end);

        // prefer attributes from source drug
        String form = sourceDrug.getForm() != null ? sourceDrug.getForm() : targetDrug.getForm();
        String brand = sourceDrug.getBrand() != null ? sourceDrug.getBrand() : targetDrug.getBrand();

        mergedDrug.setForm(form);
        mergedDrug.setBrand(brand);

        ImmutableSet<FeatureStructure> mergedAttributes = null;

        if (sourceDrug.getAttrs() != null && targetDrug.getAttrs() != null) {
            mergedAttributes = Stream.of(
                    ImmutableList.copyOf(sourceDrug.getAttrs()),
                    ImmutableList.copyOf(targetDrug.getAttrs()))
                    .flatMap(ImmutableList::stream)
                    .collect(ImmutableSet.toImmutableSet());
        } else if (sourceDrug.getAttrs() != null) {
            mergedAttributes = ImmutableList.copyOf(sourceDrug.getAttrs()).stream()
                    .collect(ImmutableSet.toImmutableSet());
        } else if (targetDrug.getAttrs() != null) {
            mergedAttributes = ImmutableList.copyOf(targetDrug.getAttrs()).stream()
                    .collect(ImmutableSet.toImmutableSet());
        }

        if (mergedAttributes != null && mergedAttributes.size() > 0) {
            FSArray medAttrs = new FSArray(jcas, mergedAttributes.size());

            medAttrs.copyFromArray(mergedAttributes
                    .toArray(new FeatureStructure[0]), 0, 0, mergedAttributes.size());

            mergedDrug.setAttrs(medAttrs);
        }

        ImmutableSet<FeatureStructure> mergedIngredients = null;

        if (sourceDrug.getIngredients() != null && targetDrug.getIngredients() != null) {
            mergedIngredients = Stream.of(
                    ImmutableList.copyOf(sourceDrug.getIngredients()),
                    ImmutableList.copyOf(targetDrug.getIngredients()))
                    .flatMap(ImmutableList::stream)
                    .collect(ImmutableSet.toImmutableSet());
        } else if (sourceDrug.getIngredients() != null) {
            mergedIngredients = ImmutableList.copyOf(sourceDrug.getIngredients()).stream()
                    .collect(ImmutableSet.toImmutableSet());
        } else if (targetDrug.getIngredients() != null) {
            mergedIngredients = ImmutableList.copyOf(targetDrug.getIngredients()).stream()
                    .collect(ImmutableSet.toImmutableSet());
        }

        if (mergedIngredients != null && mergedIngredients.size() > 0) {
            FSArray medIngredients = new FSArray(jcas, mergedIngredients.size());

            medIngredients.copyFromArray(mergedIngredients
                    .toArray(new FeatureStructure[0]), 0, 0, mergedIngredients.size());

            mergedDrug.setIngredients(medIngredients);
        }

        sourceDrug.removeFromIndexes(jcas);
        targetDrug.removeFromIndexes(jcas);
        mergedDrug.addToIndexes(jcas);

    }
}