package io.crate.analyze;

import com.google.common.collect.ImmutableList;
import io.crate.metadata.*;
import io.crate.metadata.sys.SysExpression;
import io.crate.operator.operator.*;
import io.crate.operator.reference.sys.NodeLoadExpression;
import io.crate.operator.reference.sys.SysObjectReference;
import io.crate.planner.RowGranularity;
import io.crate.planner.symbol.*;
import org.cratedb.DataType;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class EvaluatingNormalizerTest {

    private ReferenceResolver referenceResolver;
    private Functions functions;
    private ReferenceIdent dummyLoadReference;
    private ReferenceInfo dummyLoadInfo;

    @Before
    public void setUp() throws Exception {
        Map<ReferenceIdent, ReferenceImplementation> referenceImplementationMap = new HashMap<>(1, 1);

        dummyLoadReference = new ReferenceIdent(new TableIdent("test", "dummy"), "load");
        dummyLoadInfo = new ReferenceInfo(dummyLoadReference, RowGranularity.NODE, DataType.DOUBLE);

        referenceImplementationMap.put(dummyLoadReference, new SysExpression<Double>() {
            @Override
            public Double value() {
                return 0.08;
            }

            @Override
            public ReferenceInfo info() {
                return dummyLoadInfo;
            }
        });

        functions = new ModulesBuilder().add(new OperatorModule())
                .createInjector()
                .getInstance(Functions.class);

        referenceResolver = new GlobalReferenceResolver(referenceImplementationMap);
    }

    @Test
    public void testEvaluation() {

        EvaluatingNormalizer visitor = new EvaluatingNormalizer(
                functions, RowGranularity.NODE, referenceResolver);

        /**
         * prepare the following where clause as function symbol tree:
         *
         *  where load['1'] = 0.08 or name != 'x' and name != 'y'
          */

        Reference load_1 = new Reference(dummyLoadInfo);
        DoubleLiteral d01 = new DoubleLiteral(0.08);
        Function load_eq_01 = new Function(
                functionInfo(EqOperator.NAME, DataType.DOUBLE), Arrays.<Symbol>asList(load_1, d01));

        ValueSymbol name_ref = new Reference(
                new ReferenceInfo(
                        new ReferenceIdent(new TableIdent(null, "foo"), "name"),
                        RowGranularity.DOC,
                        DataType.STRING
                )
        );
        ValueSymbol x_literal = new StringLiteral("x");
        ValueSymbol y_literal = new StringLiteral("y");

        Function name_neq_x = new Function(
                functionInfo(NotEqOperator.NAME, DataType.STRING), Arrays.<Symbol>asList(name_ref, x_literal));

        Function name_neq_y = new Function(
                functionInfo(NotEqOperator.NAME, DataType.STRING), Arrays.<Symbol>asList(name_ref, y_literal));

        Function op_and = new Function(
                functionInfo(AndOperator.NAME, DataType.BOOLEAN), Arrays.<Symbol>asList(name_neq_x, name_neq_y));

        Function op_or = new Function(
                functionInfo(OrOperator.NAME, DataType.BOOLEAN), Arrays.<Symbol>asList(load_eq_01, op_and));


        // the load['1'] == 0.08 parts evaluates to true and therefore the whole query is optimized to true
        Symbol query = visitor.process(op_or, null);
        assertThat(query, instanceOf(BooleanLiteral.class));
        assertThat(((BooleanLiteral) query).value(), is(true));
    }

    private FunctionInfo functionInfo(String name, DataType aDouble) {
        return functions.get(new FunctionIdent(name, ImmutableList.of(aDouble, aDouble))).info();
    }
}
