/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */
package com.microsoft.spring.data.gremlin.query.query;

import com.microsoft.spring.data.gremlin.common.GremlinUtils;
import com.microsoft.spring.data.gremlin.conversion.script.AbstractGremlinScriptLiteral;
import com.microsoft.spring.data.gremlin.query.criteria.Criteria;
import com.microsoft.spring.data.gremlin.query.criteria.CriteriaType;
import com.microsoft.spring.data.gremlin.repository.support.GremlinEntityInformation;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.lang.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.microsoft.spring.data.gremlin.common.Constants.*;

@NoArgsConstructor
public class QueryFindScriptGenerator implements QueryScriptGenerator {

    @Setter(AccessLevel.PRIVATE)
    private GremlinEntityInformation information;

    private String getCriteriaSubject(@NonNull Criteria criteria) {
        String subject = criteria.getSubject();

        if (subject.equals(this.information.getIdField().getName())) {
            subject = PROPERTY_ID; // If subject is @Id/id field, use id property in database.
        }

        return subject;
    }

    private String generateIsEqual(@NonNull Criteria criteria) {
        final String subject = this.getCriteriaSubject(criteria);
        final String content = AbstractGremlinScriptLiteral.generateHas(subject, criteria.getSubValues().get(0));

        return String.format(GREMLIN_PRIMITIVE_WHERE, content);
    }

    /**
     * Generate script with only one subject and no subValue, like findByActiveExists().
     *
     * @param criteria given query represent a query subject
     * @return simple script with keyword from criteria type
     */
    private String generateEmptyScript(@NonNull Criteria criteria) {
        final String subject = this.getCriteriaSubject(criteria);
        final String has = AbstractGremlinScriptLiteral.generateHas(subject, true);

        return String.format(GREMLIN_PRIMITIVE_WHERE, has);
    }

    /**
     * Generate script with only one subject and only one subValue, like findByCreateAtBefore(Date start).
     *
     * @param criteria given query represent a query subject
     * @return simple script with keyword from criteria type
     */
    private String generateSingleScript(@NonNull Criteria criteria) {
        final CriteriaType type = criteria.getType();
        final String subject = this.getCriteriaSubject(criteria);
        final long milliSeconds = GremlinUtils.timeToMilliSeconds(criteria.getSubValues().get(0));

        final String values = String.format(GREMLIN_PRIMITIVE_VALUES, subject);
        final String query = String.format(CriteriaType.criteriaTypeToGremlin(type), milliSeconds);
        final String content = String.join(GREMLIN_PRIMITIVE_INVOKE, values, query);

        return String.format(GREMLIN_PRIMITIVE_WHERE, content);
    }

    /**
     * Generate script with only one subject and two subValue, like findByCreateAtBetween(Date start, Date end).
     *
     * @param criteria given query represent a query subject
     * @return simple script with keyword from criteria type
     */
    private String generateDoubleScript(Criteria criteria) {
        final CriteriaType type = criteria.getType();
        final String subject = this.getCriteriaSubject(criteria);
        final long start = GremlinUtils.toPrimitiveLong(criteria.getSubValues().get(0));
        final long end = GremlinUtils.toPrimitiveLong(criteria.getSubValues().get(1));

        final String values = String.format(GREMLIN_PRIMITIVE_VALUES, subject);
        final String query = String.format(CriteriaType.criteriaTypeToGremlin(type), start, end);
        final String content = String.join(GREMLIN_PRIMITIVE_INVOKE, values, query);

        return String.format(GREMLIN_PRIMITIVE_WHERE, content);
    }

    /**
     * Generate script combined by AND/OR keyword.
     *
     * @param left  sub script on left
     * @param right sub script on right
     * @param type  should be AND/OR
     * @return combined script with AND/OR
     */
    private String generateCombinedScript(@NonNull String left, @NonNull String right, CriteriaType type) {
        final String operation = CriteriaType.criteriaTypeToGremlin(type);
        final String content = String.join(GREMLIN_PRIMITIVE_INVOKE, left, operation, right);

        return String.format(GREMLIN_PRIMITIVE_WHERE, content);
    }


    private String generateScriptTraversal(@NonNull Criteria criteria) {
        final CriteriaType type = criteria.getType();

        switch (type) {
            case IS_EQUAL:
                return this.generateIsEqual(criteria);
            case AND:
            case OR:
                final String left = this.generateScriptTraversal(criteria.getSubCriteria().get(0));
                final String right = this.generateScriptTraversal(criteria.getSubCriteria().get(1));

                return this.generateCombinedScript(left, right, type);
            case AFTER:
            case BEFORE:
                return this.generateSingleScript(criteria);
            case BETWEEN:
                return this.generateDoubleScript(criteria);
            case EXISTS:
                return this.generateEmptyScript(criteria);
            default:
                throw new UnsupportedOperationException("unsupported Criteria type");
        }
    }

    private List<String> generateScript(@NonNull GremlinQuery query) {
        final Criteria criteria = query.getCriteria();
        final List<String> scriptList = new ArrayList<>();

        scriptList.add(GREMLIN_PRIMITIVE_GRAPH);

        if (this.information.isEntityVertex()) {
            scriptList.add(GREMLIN_PRIMITIVE_VERTEX_ALL);
        } else if (this.information.isEntityEdge()) {
            scriptList.add(GREMLIN_PRIMITIVE_EDGE_ALL);
        } else {
            throw new UnsupportedOperationException("Cannot generate script from graph entity");
        }

        scriptList.add(String.format(GREMLIN_PRIMITIVE_HAS_STRING, PROPERTY_LABEL, this.information.getEntityLabel()));
        scriptList.add(this.generateScriptTraversal(criteria));

        return scriptList;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<String> generate(@NonNull GremlinQuery query, @NonNull Class<T> domainClass) {

        this.setInformation(new GremlinEntityInformation(domainClass));

        final List<String> scriptList = new ArrayList<>(this.generateScript(query));

        return Collections.singletonList(String.join(GREMLIN_PRIMITIVE_INVOKE, scriptList));
    }
}

