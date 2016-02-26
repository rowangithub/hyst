package com.verivital.hyst.junit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.verivital.hyst.geometry.HyperPoint;
import com.verivital.hyst.geometry.HyperRectangle;
import com.verivital.hyst.geometry.Interval;
import com.verivital.hyst.grammar.formula.Constant;
import com.verivital.hyst.grammar.formula.DefaultExpressionPrinter;
import com.verivital.hyst.grammar.formula.Expression;
import com.verivital.hyst.grammar.formula.FormulaParser;
import com.verivital.hyst.ir.AutomatonExportException;
import com.verivital.hyst.ir.Configuration;
import com.verivital.hyst.ir.base.AutomatonMode;
import com.verivital.hyst.ir.base.AutomatonTransition;
import com.verivital.hyst.ir.base.BaseComponent;
import com.verivital.hyst.ir.base.ExpressionInterval;
import com.verivital.hyst.passes.complex.hybridize.HybridizeGridPass;
import com.verivital.hyst.passes.complex.hybridize.HybridizeMixedTriggeredPass;
import com.verivital.hyst.python.PythonBridge;
import com.verivital.hyst.util.AutomatonUtil;
import com.verivital.hyst.util.RangeExtractor;

@RunWith(Parameterized.class)
public class HybridizePassTests
{
	@Before
	public void setUpClass()
	{
		Expression.expressionPrinter = null;
	}

	@Parameters
	public static Collection<Object[]> data()
	{
		return Arrays.asList(new Object[][] { { false }, { true } });
	}

	public HybridizePassTests(boolean block)
	{
		PythonBridge.setBlockPython(block);
	}

	/**
	 * An ExpresssionPrinter which prints constants to a certain number of
	 * digits after the decimel
	 *
	 */
	private class RoundPrinter extends DefaultExpressionPrinter {
		public RoundPrinter(int digits) {
			super();
			constFormatter.setMaximumFractionDigits(digits);
		}
	}
	/**
	 * make a sample base configuration with a single mode, with x' == 1, and y'
	 * == 1
	 * 
	 * @return the constructed Configuration
	 */
	private static Configuration makeSampleBaseConfiguration()
	{
		String[][] dynamics = { { "x", "1" }, { "y", "1" } };

		return AutomatonUtil.makeDebugConfiguration(dynamics);
	}

	/**
	 * Check two expressions for equality, raising an assertion exception if
	 * there are errors
	 */
	void assertExpressionsEqual(String message, Expression expected,
			Expression actual)
	{
		String msg = AutomatonUtil.areExpressionsEqual(expected, actual);

		if (msg != null)
			Assert.fail(message + "\n" + msg);
	}

	/**
	 * Test hybridization (grid) pass
	 */
	@Test
	public void testHybridGridPass()
	{
		if (!PythonBridge.hasPython())
			return;

		Configuration c = makeSampleBaseConfiguration();
		BaseComponent ha = (BaseComponent) c.root;

		// update dynamics to be 3*t*x+t
		// approximation should be 7.5*x + 5.5*t
		// rest is in notebook
		AutomatonMode am = ha.modes.values().iterator().next();
		am.flowDynamics.put("x", new ExpressionInterval("3*y*x+y"));

		String params = "x,y,0,2,0,5,2,5,a";

		try
		{
			new HybridizeGridPass().runTransformationPass(c, params);

			Assert.assertEquals("10 modes", 10, ha.modes.size());

			AutomatonMode m = ha.modes.get("_m_1_2");
			Assert.assertNotEquals("mode named '_m_1_2 exists'", null, m);

			// dynamics should be y' == 7.5*x + 5.5*t + [-12, -10.5]
			Expression.expressionPrinter = new RoundPrinter(3);
			ExpressionInterval ei = m.flowDynamics.get("x");

			Expression e = ei.getExpression();
			Interval i = ei.getInterval();

			// expected "7.5 * x + 5.5 * y + -12 + [0, 1.5]"
			double TOL = 1e-9;

			Assert.assertEquals(
					"Hybrizied mode x=[1,2], y=[2,3] in correctly (interval min wrong)",
					0, i.min, TOL);
			Assert.assertEquals(
					"Hybrizied mode x=[1,2], y=[2,3] in correctly (interval max wrong)",
					1.5, i.max, TOL);

			Expression correctE = FormulaParser
					.parseValue("7.5 * x + 5.5 * y - 12");
			String msg = AutomatonUtil.areExpressionsEqual(correctE, e);

			if (msg != null)
				Assert.fail(msg);

			Assert.assertEquals("single initial state", c.init.size(), 1);
		}
		catch (AutomatonExportException ex)
		{
			Assert.assertEquals(AutomatonExportException.class, ex.getClass()); // vacuously
																				// true,
																				// but
																				// will
																				// force
																				// failure
																				// if
																				// different
																				// error
		}
	}

	/**
	 * Test hybridization (time-triggered) pass
	 */
	@Test
	public void testHybridMixedTriggeredPass()
	{
		if (!PythonBridge.hasPython())
			return;

		RoundPrinter rp = new RoundPrinter(4);
		Configuration c = makeSampleBaseConfiguration();
		BaseComponent ha = (BaseComponent) c.root;
		AutomatonMode am = ha.modes.values().iterator().next();

		// we're going to follow the example in the powerpoint for this
		ha.variables.remove("y");
		am.flowDynamics.remove("y");
		am.flowDynamics.put("x", new ExpressionInterval("x^2"));
		am.invariant = FormulaParser.parseInvariant("x <= 10");

		c.settings.plotVariableNames[1] = "x";
		c.init.put("on", FormulaParser.parseGuard("x >= 0.24 & x <= 0.26"));
		c.validate();

		String params = "step=0.5,maxtime=1.0,epsilon=0.05,simtype=center";

		try
		{
			new HybridizeMixedTriggeredPass().runTransformationPass(c, params);

			Assert.assertEquals("5 modes (2 + 3 error)", 5, ha.modes.size());
			Assert.assertEquals("1 initial mode", 1, c.init.size());
			Assert.assertTrue("variable _tt exists",
					ha.variables.contains("_tt"));

			TreeMap<String, Interval> ranges = RangeExtractor.getVariableRanges(
					c.init.values().iterator().next(), "initial states");
			Assert.assertEquals("_tt is initially 0.5", 0.5,
					ranges.get("_tt").asConstant(), 1e-12);

			AutomatonMode m0 = ha.modes.get("_m_0");
			Assert.assertNotEquals("mode named '_m_0 exists'", null, m0);

			Expression.expressionPrinter = rp;

			AutomatonMode m1 = ha.modes.get("_m_1");
			Assert.assertNotEquals("mode named '_m_1 exists'", null, m1);

			// invariant x <= 10 should be present in first mode
			// time trigger invariant c <= 0.5 should be present in first mode
			// as well
			// should be x <= 10 & _tt >= 0 & x >= 0.2 & x <= 0.3357
			Assert.assertEquals("mode0 invariant correct",
					"x <= 10 & _tt >= 0 & x >= 0.2 & x <= 0.3357",
					m0.invariant.toString());

			// mode 1 invariant correct
			// should be c <= 1 & x >= 0.2357 & x <= 0.3833
			Assert.assertEquals("mode1 invariant correct",
					"x <= 10 & _tt >= 0 & x >= 0.2357 & x <= 0.3833",
					m1.invariant.toString());
		}
		catch (AutomatonExportException ex)
		{
			Assert.assertEquals(AutomatonExportException.class, ex.getClass()); // vacuously
																				// true,
																				// but
																				// will
																				// force
																				// failure
																				// if
																				// different
																				// error
		}
	}

	/**
	 * Test hybridization (time-triggered) pass
	 */
	@Test
	public void testHybridizeMixedTriggeredPassWithPremodes()
	{
		if (!PythonBridge.hasPython())
			return;

		RoundPrinter rp = new RoundPrinter(4);
		Configuration c = makeSampleBaseConfiguration();
		BaseComponent ha = (BaseComponent) c.root;
		AutomatonMode am = ha.modes.values().iterator().next();

		// we're going to follow the example in the powerpoint for this
		ha.variables.remove("y");
		am.flowDynamics.remove("y");
		am.flowDynamics.put("x", new ExpressionInterval("x^2"));
		am.invariant = FormulaParser.parseInvariant("x <= 10");

		c.settings.plotVariableNames[1] = "x";
		c.init.put("on", FormulaParser.parseGuard("x >= 0.24 & x <= 0.26"));
		c.validate();

		String params = "step=0.5,maxtime=1.0,epsilon=0.05,simtype=center,addintermediate=true";

		try
		{
			new HybridizeMixedTriggeredPass().runTransformationPass(c, params);

			Assert.assertEquals("6 modes (2 + premode + 3 errors)", 6,
					ha.modes.size());
			Assert.assertEquals("1 initial mode", 1, c.init.size());
			Assert.assertTrue("variable _tt exists",
					ha.variables.contains("_tt"));

			AutomatonMode m0 = ha.modes.get("_m_0");
			Assert.assertNotEquals("mode named '_m_0 exists'", null, m0);

			Expression.expressionPrinter = rp;

			// dynamics should be approximately x' =.536*x - 0.0718 + [0,
			// 0.0046]
			double TOL = 1e-2;
			ComparableEi correctDynamics = new ComparableEi(
					"0.5357 * x - 0.0717", new Interval(0, 0.0046), TOL);
			ComparableEi computedDynamics = new ComparableEi(
					m0.flowDynamics.get("x"), TOL);

			Assert.assertEquals("mode0.x' incorrect", correctDynamics,
					computedDynamics);

			AutomatonMode m1 = ha.modes.get("_m_1");
			Assert.assertNotEquals("mode named '_m_1 exists'", null, m1);

			// dynamics should be approx x=0.619 * x + -0.0958 + [0, 0.0054]
			correctDynamics = new ComparableEi("0.619 * x - 0.0958)",
					new Interval(0, 0.0054), TOL);
			Assert.assertEquals("mode0.x' incorrect", correctDynamics,
					new ComparableEi(m1.flowDynamics.get("x"), TOL));

			// invariant x <= 10 should be present in first mode
			// time trigger invariant c <= 0.5 should be present in first mode
			// as well
			// should be x <= 10 & _tt >= 0 & x >= 0.2 & x <= 0.3357
			Assert.assertEquals("mode0 invariant correct",
					"x <= 10 & _tt >= 0 & x >= 0.2 & x <= 0.3357",
					m0.invariant.toString());

			// mode 1 invariant correct
			// should be c <= 1 & x >= 0.2357 & x <= 0.3833
			Assert.assertEquals("mode1 invariant correct",
					"x <= 10 & _tt >= 0 & x >= 0.2357 & x <= 0.3833",
					m1.invariant.toString());

			// error transitions should exist from the first mode at the time
			// trigger
			// error transitions should exist in the first mode due to the
			// hyperrectangle constraints
			int numTransitions = 0;
			boolean foundTriggerTransition = false;
			boolean foundOobTransition = false;

			for (AutomatonTransition at : ha.transitions)
			{
				if (at.from == m0)
				{
					++numTransitions;

					if (at.to == m1 && at.guard.toString().equals("_tt = 0"))
						foundTriggerTransition = true;

					if (at.to.name.equals("_error_tt_inv_m_0")
							&& at.guard.toString().equals("x >= 0.3357"))
						foundOobTransition = true;
				}
			}

			Assert.assertTrue("transition exists at time trigger in mode0",
					foundTriggerTransition);
			Assert.assertTrue(
					"transition to out of bounds error mode exists in mode0",
					foundOobTransition);

			Assert.assertEquals(
					"wrong number of outgoing transitions from mode0, expected 6 "
							+ "(tt, premode, x-too-small, x-too-large, x-too-small-at-tt, x-too-large-at-tt)",
					6, numTransitions);

			Assert.assertEquals("three forbidden modes (inv1, guard2, inv2)", 3,
					c.forbidden.size());
		}
		catch (AutomatonExportException ex)
		{
			Assert.assertEquals(AutomatonExportException.class, ex.getClass()); // vacuously
																				// true,
																				// but
																				// will
																				// force
																				// failure
																				// if
																				// different
																				// error
		}
	}

	@Test
	public void testHybridizeMixedTriggeredPassVanderpol()
	{
		if (!PythonBridge.hasPython())
			return;

		// test that dynamics in mode zero should be exactly x' == y
		// params: step=0.01,maxtime=0.02,epsilon=0.001,addforbidden=false
		Configuration c = makeSampleBaseConfiguration();
		BaseComponent ha = (BaseComponent) c.root;
		AutomatonMode am = ha.modes.values().iterator().next();

		am.flowDynamics.put("x", new ExpressionInterval("y"));
		am.flowDynamics.put("y", new ExpressionInterval("(1-x*x)*y-x"));
		c.init.put("on", FormulaParser.parseInitialForbidden(
				"-0.51 <= x & x <= -0.5 & -2.61 <= y & y <= -2.6"));
		c.validate();

		String params = "step=0.01,maxtime=0.02,epsilon=0.01,addforbidden=false";

		try
		{
			new HybridizeMixedTriggeredPass().runTransformationPass(c, params);

			Assert.assertEquals("3 modes (2 + 3 error)", 5, ha.modes.size());
			Assert.assertEquals("1 initial mode", 1, c.init.size());

			AutomatonMode m0 = ha.modes.get("_m_0");
			Assert.assertNotEquals("mode named '_m_0 exists'", null, m0);

			// dynamics should be x' == y
			ExpressionInterval ei = m0.flowDynamics.get("x");
			// 0.9999999999999869 * y + -0.000000000000034638958368304884
			double coeff = ((Constant) ei.getExpression().asOperation()
					.getLeft().asOperation().getLeft()).getVal();

			Assert.assertTrue("dynamics in mode0 for x was y",
					Math.abs(coeff - 1.0) < 1e-13);
		}
		catch (AutomatonExportException ex)
		{
			Assert.assertEquals(AutomatonExportException.class, ex.getClass()); // vacuously
																				// true,
																				// but
																				// will
																				// force
																				// failure
																				// if
																				// different
																				// error
		}
	}

	@Test
	public void testCheckHyperPlane()
	{
		// tests the HybridizeTimeTriggeredPass.testHyperPlane() function
		// this function tests whether a state-triggered transition constructed
		// at the
		// given point would be on one side (in front of) of a given box

		// we'll use a 1d model with x' = 1, and check if the box [1,2] is on
		// one side of x = {0.5, 1.5, 2.5}. Only 2.5 should be okay.
		Map<String, Expression> dy = new HashMap<String, Expression>();
		dy.put("x", new Constant(1));

		List<String> vars = new ArrayList<String>();
		vars.add("x");

		HyperRectangle box = new HyperRectangle(new Interval(1, 2));
		double pts[] = { -0.5, 0.5, 1.5, 2.5 };
		boolean expected[] = { false, false, false, true };

		runBoxTests(vars, dy, box, pts, expected);

		// try again with [-2, -1] and -2.5, -1.5, -0.5, and 0.5
		box = new HyperRectangle(new Interval(-2, -1));
		pts = new double[] { -2.5, -1.5, -0.5, 0.5 };
		expected = new boolean[] { false, false, true, true };
		runBoxTests(vars, dy, box, pts, expected);

		// try again with dynamics: x' == -1
		dy.put("x", new Constant(-1));
		box = new HyperRectangle(new Interval(1, 2));
		pts = new double[] { -0.5, 0.5, 1.5, 2.5 };
		expected = new boolean[] { true, true, false, false };
		runBoxTests(vars, dy, box, pts, expected);

		// try again with negative box and dynamics: x' == -1
		box = new HyperRectangle(new Interval(-2, -1));
		pts = new double[] { -2.5, -1.5, -0.5, 0.5 };
		expected = new boolean[] { true, false, false, false };
		runBoxTests(vars, dy, box, pts, expected);

		// try again with box [-1, 1] and dynamics: x' == -1
		box = new HyperRectangle(new Interval(-1, 1));
		pts = new double[] { -1.5, -0.5, 0, 0.5, 1.5 };
		expected = new boolean[] { true, false, false, false, false };
		runBoxTests(vars, dy, box, pts, expected);
	}

	/**
	 * helper method for testCheckHyperPlane
	 * 
	 * @param vars
	 *            the variable name list
	 * @param dy
	 *            the dynamics
	 * @param box
	 *            the box
	 * @param pts
	 *            the set of points to test
	 * @param expected
	 *            the expected results
	 */
	private void runBoxTests(List<String> vars, Map<String, Expression> dy,
			HyperRectangle box, double[] pts, boolean[] expected)
	{
		BaseComponent ha = new BaseComponent();

		AutomatonMode am = ha.createMode("on");
		am.flowDynamics = new LinkedHashMap<String, ExpressionInterval>();

		for (Entry<String, Expression> e : dy.entrySet())
			am.flowDynamics.put(e.getKey(),
					new ExpressionInterval(e.getValue()));

		am.automaton.variables.addAll(vars);

		if (pts.length != expected.length)
			throw new RuntimeException(
					"pts.length should be equal to expected.length");

		Expression.expressionPrinter = DefaultExpressionPrinter.instance;

		for (int i = 0; i < pts.length; ++i)
		{
			Assert.assertTrue(pts[i] + (expected[i] ? " SHOULD" : " should NOT")
					+ " be in front of box " + box + ". dynamics: " + dy,
					HybridizeMixedTriggeredPass.testHyperPlane(
							new HyperPoint(pts[i]), box, am) == expected[i]);
		}
	}

	@Test
	public void testMixedTriggeredHybridizeWithPi()
	{
		if (!PythonBridge.hasPython())
			return;

		// time-triggered hybridized pass tests with state-triggered transitions
		// 1d system with x'==1, init box is [0, 1], use star to construct guide
		// simulation
		// state-triggered count is 1, which means it should be constructed
		// right around x == 1 (the edge of the box)

		Configuration c = makeSampleBaseConfiguration();
		BaseComponent ha = (BaseComponent) c.root;
		AutomatonMode am = ha.modes.values().iterator().next();

		// we're going to follow the example in the powerpoint for this
		ha.variables.remove("y");
		am.flowDynamics.remove("y");
		am.flowDynamics.put("x", new ExpressionInterval("1"));

		c.settings.plotVariableNames[1] = "x";
		c.init.put("on", FormulaParser.parseGuard("x >= 0 & x <= 1"));
		c.validate();

		String params = "step=1,maxtime=10,epsilon=0.01,simtype=star,picount=1";

		try
		{
			HybridizeMixedTriggeredPass htt = new HybridizeMixedTriggeredPass();
			htt.testFuncs = new HybridizeMixedTriggeredPass.TestFunctions()
			{
				@Override
				public void piSimPointsReached(List<HyperPoint> simPoints)
				{
					Assert.assertEquals("3 sim points after construction", 3,
							simPoints.size());

					for (HyperPoint sp : simPoints)
						Assert.assertEquals("simpoint is near x == 1.05",
								simPoints.get(0).dims[0], sp.dims[0], 1e-4);
				}

				@Override
				public void initialSimPoints(List<HyperPoint> simPoints)
				{
					Assert.assertEquals("3 initial sim points", 3,
							simPoints.size());
				}

				@Override
				public void piSucceeded(boolean rv)
				{
					Assert.assertTrue("pi should succeed", rv);
				}
			};

			htt.runTransformationPass(c, params);

			final String FIRST_MODE = "_m_0";
			final String SECOND_MODE = "_m_1";
			final String TT_VAR = "_tt";

			AutomatonMode m0 = ha.modes.get(FIRST_MODE);
			Assert.assertNotEquals("first mode exists'", null, m0);

			AutomatonMode m1 = ha.modes.get(SECOND_MODE);
			Assert.assertNotEquals("second mode exists", null, m1);

			Assert.assertEquals("tt derivative in first mode is zero",
					((Constant) m0.flowDynamics.get(TT_VAR).asExpression())
							.getVal(),
					0, 1e-9);
			int numTransitions = 0;
			boolean foundGuard = false;

			for (AutomatonTransition at : ha.transitions)
			{
				if (at.from == m0)
				{
					++numTransitions;

					if (at.to == m1 && at.guard.toDefaultString()
							.contains("1 * x >= 1.050000"))
						foundGuard = true;
				}
			}

			Assert.assertEquals("five transitions from first mode", 5,
					numTransitions);
			Assert.assertTrue("found pi guard", foundGuard);

			Assert.assertTrue("pi guard is exists",
					m0.invariant.toDefaultString().contains("1 * x <= 1.0500"));

			Assert.assertTrue("second mode's invariant starts at 1.04",
					m1.invariant.toDefaultString().contains("x >= 1.040000"));
		}
		catch (AutomatonExportException ex)
		{
			Assert.assertEquals(AutomatonExportException.class, ex.getClass()); // vacuously
																				// true,
																				// but
																				// will
																				// force
																				// failure
																				// if
																				// different
																				// error
		}
	}

}
