/*
 * DecompositionTest.scala
 * Tests for decomposition strategies.
 *
 * Created By:      William Kretschmer (kretsch@mit.edu)
 * Creation Date:   Oct 18, 2016
 *
 * Copyright 2016 Avrom J. Pfeffer and Charles River Analytics, Inc.
 * See http://www.cra.com or email figaro@cra.com for information.
 *
 * See http://www.github.com/p2t2/figaro for a copy of the software license.
 */
package com.cra.figaro.test.algorithm.structured.strategy.refine

import com.cra.figaro.algorithm.lazyfactored.Regular
import com.cra.figaro.algorithm.structured.strategy.refine._
import com.cra.figaro.algorithm.structured.strategy.solve._
import com.cra.figaro.algorithm.structured._
import com.cra.figaro.algorithm.structured.solver._
import com.cra.figaro.language._
import com.cra.figaro.library.atomic.continuous.{Beta, Normal}
import com.cra.figaro.library.atomic.discrete._
import com.cra.figaro.library.compound.If
import org.scalatest.{Matchers, WordSpec}

import scala.collection.mutable

class DecompositionTest extends WordSpec with Matchers {
  "A complete decomposition strategy" should {
    "create ranges for all components" in {
      Universe.createNew()
      val e1 = Flip(0.3)
      val e2 = If(e1, Constant(0), Uniform(2, 3, 4))
      val cc = new ComponentCollection
      val pr = new Problem(cc, List(e2))
      new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

      val c1 = cc(e1)
      val c2 = cc(e2)
      c1.range.regularValues should equal(Set(true, false))
      c1.range.hasStar should be(false)
      c2.range.regularValues should equal(Set(0, 2, 3, 4))
      c2.range.hasStar should be(false)
      val c1t = cc(c2.subproblems(true).target)
      val c1f = cc(c2.subproblems(false).target)
      c1t.range.regularValues should equal(Set(0))
      c1t.range.hasStar should be(false)
      c1f.range.regularValues should equal(Set(2, 3, 4))
      c1f.range.hasStar should be(false)
    }

    "sample continuous components with the rangeSizer" in {
      Universe.createNew()
      val e1 = Normal(0, 1)
      val cc = new ComponentCollection
      val pr = new Problem(cc, List(e1))
      val rangeSizer = (pc: ProblemComponent[_]) => 30
      new FullDecompositionStrategy(pr, rangeSizer, false).execute()

      cc(e1).variable.size should be(30)
    }

    "create non-constraint factors for all components" in {
      Universe.createNew()
      val e1 = Flip(0.3)
      val e2 = If(e1, Constant(0), Uniform(2, 3, 4))
      val cc = new ComponentCollection
      val pr = new Problem(cc, List(e2))
      new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

      val c1 = cc(e1)
      val c2 = cc(e2)
      // Each component has 1 factor
      // Chain components additionally have 1 factor per subproblem
      c1.nonConstraintFactors should have size 1
      c2.nonConstraintFactors should have size 3

      val c1t = cc(c2.subproblems(true).target)
      val c1f = cc(c2.subproblems(false).target)
      c1t.nonConstraintFactors should have size 1
      c1f.nonConstraintFactors should have size 1
    }

    "create upper and lower bound constraint factors for top-level components" in {
      Universe.createNew()
      val e1 = Uniform(1, 2, 3)
      e1.addConstraint((i: Int) => 1.0 / i)
      val cc = new ComponentCollection
       val pr = new Problem(cc, List(e1))
      new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

      val c1 = cc(e1)
      c1.constraintFactors(Lower) should have size 1
      c1.constraintFactors(Upper) should have size 1
    }

    // TODO parameters seemingly don't work in SFI
    /*"conditionally make parameterized factors" when {
      "parameterized is set to true" in {
        Universe.createNew()
        val e1 = Beta(1, 2)
        val e2 = Flip(e1)
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1, e2))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()
      }

      "parameterized is set to false" in {
        Universe.createNew()
        val e1 = Beta(1, 2)
        val e2 = Flip(e1)
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1, e2))
        new FullDecompositionStrategy(pr, defaultRangeSizer, true).execute()
      }
    }*/

    "preserve solutions to solved subproblems" in {
      Universe.createNew()
      val e1 = Flip(0.3)
      val e2 = If(e1, Select(0.1 -> 1, 0.9 -> 2), Uniform(3, 4))
      val cc = new ComponentCollection
      val pr = new Problem(cc, List(e1, e2))
      val c1 = cc(e1)
      val c2 = cc(e2)
      c1.generateRange()
      c2.expand()
      // Decompose and solve the subproblem corresponding to true
      val spr = c2.subproblems(true)
      new FullDecompositionStrategy(spr, defaultRangeSizer, false).execute()
      new ConstantStrategy(spr, structured, marginalVariableElimination).execute()
      // This should not get rid of the solution
      new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

      spr.solved should be(true)
      val solution = spr.solution.head
      val range = cc(spr.target).variable.range
      solution.get(List(range.indexOf(Regular(1)))) should be(0.1 +- 0.000000001)
      solution.get(List(range.indexOf(Regular(2)))) should be(0.9 +- 0.000000001)
    }

    "mark visited components as done" in {
      Universe.createNew()
      val e1 = Flip(0.3)
      val e2 = If(e1, Select(0.1 -> 1, 0.9 -> 2), Uniform(3, 4))
      val cc = new ComponentCollection
      val pr = new Problem(cc, List(e1, e2))
      val done = mutable.Set[ProblemComponent[_]]()
      new FullDecompositionStrategy(pr, defaultRangeSizer, false, done).execute()
      done.size should be(4)
    }

    "not decompose components in the done set" in {
      Universe.createNew()
      val e1 = Uniform(1, 2, 3)
      val cc = new ComponentCollection
      val pr = new Problem(cc, List(e1))
      val c1 = cc(e1)
      val done = mutable.Set[ProblemComponent[_]](c1)
      new FullDecompositionStrategy(pr, defaultRangeSizer, false, done).execute()

      c1.variable.valueSet.regularValues should be(empty)
      c1.nonConstraintFactors should be(empty)
      c1.constraintFactors(Lower) should be(empty)
    }

    "correctly mark components as fully enumerated and refined" when {
      "a top-level component has finite support" in {
        Universe.createNew()
        val e1 = Select(0.1 -> 1, 0.2 -> 3, 0.3 -> 5, 0.5 -> 7)
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

        cc(e1).fullyEnumerated should be(true)
        cc(e1).fullyRefined should be(true)
      }

      // TODO ranges for elements with infinite support currently do not work
      /*"a top-level component is continuous" in {
        Universe.createNew()
        val e1 = Normal(0, 1)
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

        cc(e1).fullyEnumerated should be(false)
        cc(e1).fullyRefined should be(false)
      }

      "a top-level component is discrete with infinite support" in {
        Universe.createNew()
        val e1 = Poisson(3)
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

        cc(e1).fullyEnumerated should be(false)
        cc(e1).fullyRefined should be(false)
      }*/

      "the parents of a top-level component have finite support" in {
        Universe.createNew()
        val e1 = Select(0.1 -> 1, 0.2 -> 3, 0.3 -> 5, 0.5 -> 7)
        val e2 = Select(0.2 -> 3, 0.8 -> 4)
        val e3 = e1 ++ e2
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e3))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

        cc(e3).fullyEnumerated should be(true)
        cc(e3).fullyRefined should be(true)
      }

      /*
      "the parent of a top-level component has infinite support" in {
        Universe.createNew()
        val e1 = Poisson(3)
        val e2 = Select(0.2 -> 3, 0.8 -> 4)
        val e3 = e1 ++ e2
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e3))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

        cc(e3).fullyEnumerated should be(false)
        cc(e3).fullyRefined should be(false)
      }

      "a Chain's parent has infinite support and its result elements have finite support" in {
        Universe.createNew()
        val e1 = Normal(0, 1)
        val e2 = Chain(e1, (d: Double) => if(d < 0) Flip(0.2) else Flip(0.7))
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e2))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

        val c2 = cc(e2)
        c2.fullyEnumerated should be(false)
        c2.fullyRefined should be(false)
        for((value, subproblem) <- c2.subproblems) {
          cc(subproblem.target).fullyEnumerated should be(true)
          subproblem.fullyRefined should be(true)
        }
      }

      "a Chain's result has infinite support" in {
        Universe.createNew()
        val e1 = Flip(0.3)
        val e2 = If(e1, Constant(0.0), Normal(0, 1))
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e2))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

        val c2 = cc(e2)
        c2.fullyEnumerated should be(false)
        c2.fullyRefined should be(false)
        // The Constant(0.0) should be fully expanded, the Normal(0, 1) should not
        val prt = c2.subproblems(true)
        cc(prt.target).fullyEnumerated should be(true)
        prt.fullyRefined should be(true)
        val prf = c2.subproblems(false)
        cc(prf.target).fullyEnumerated should be(false)
        prf.fullyRefined should be(false)
      }*/

      "a Chain whose parent has finite support has fully expanded all of its subproblems" in {
        Universe.createNew()
        val e1 = Select(0.1 -> 1, 0.2 -> 3, 0.3 -> 5, 0.4 -> 7)
        val e2 = Chain(e1, (i: Int) => FromRange(0, i))
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e2))
        new FullDecompositionStrategy(pr, defaultRangeSizer, false).execute()

        val c2 = cc(e2)
        c2.fullyEnumerated should be(true)
        c2.fullyRefined should be(true)
        for((value, subproblem) <- c2.subproblems) {
          cc(subproblem.target).fullyEnumerated should be(true)
          subproblem.fullyRefined should be(true)
        }
      }
    }
  }

  "A lazy decomposition strategy" when {
    // An simple recursive element; useful for testing lazy partial expansion
    def geometric(): Element[Int] = If(Flip(0.5), Constant(1), geometric().map(_ + 1))

    "called once" should {
      "produce the correct range" in {
        Universe.createNew()
        val e1 = geometric()
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1))
        new LazyDecompositionStrategy(3, pr, defaultRangeSizer, false).execute()

        val c1 = cc(e1)
        c1.range.regularValues should equal(Set(1, 2, 3))
        c1.range.hasStar should be(true)
      }

      "mark components as fully refined and enumerated" in {
        Universe.createNew()
        val e1 = geometric()
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1))
        new LazyDecompositionStrategy(3, pr, defaultRangeSizer, false).execute()

        val c1 = cc(e1).asInstanceOf[ChainComponent[Boolean, Int]]
        c1.fullyEnumerated should be(false)
        c1.fullyRefined should be(false)
        val prt = c1.subproblems(true)
        cc(prt.target).fullyEnumerated should be(true)
        prt.fullyRefined should be(true)
        val prf = c1.subproblems(false)
        cc(prf.target).fullyEnumerated should be(false)
        prf.fullyRefined should be(false)
      }

      "use the correct definition of depth with respect to globals" in {
        Universe.createNew()
        val e1 = geometric()
        val e2 = If(Flip(0.5), Constant(0), e1)
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e2))
        // TODO this test fails without the statement below, because then e1 gets added to the wrong problem.
        // Should we consider this a bug?
        pr.add(e1)
        new LazyDecompositionStrategy(3, pr, defaultRangeSizer, false).execute()

        // Even though e1 is called from the result of a Chain, it should be treated as a top-level component at depth 0
        cc(e1).range.regularValues should equal(Set(1, 2, 3))
        cc(e2).range.regularValues should equal(Set(0, 1, 2, 3))
      }
    }

    "called at increasing depth" should {
      "produce the correct range" in {
        Universe.createNew()
        val e1 = geometric()
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1))
        val c1 = cc(e1)

        for(depth <- 0 to 10) {
          new LazyDecompositionStrategy(depth, pr, defaultRangeSizer, false).execute()
          c1.range.hasStar should be(true)
          c1.range.regularValues should equal((1 to depth).toSet)
        }
      }

      "preserve only the relevant solutions" in {
        Universe.createNew()
        val e1 = geometric()
        val cc = new ComponentCollection
        val pr = new Problem(cc, List(e1))
        new LazyDecompositionStrategy(1, pr, defaultRangeSizer, false).execute()
        new ConstantStrategy(pr, structured, marginalVariableElimination).execute()

        val c1 = cc(e1).asInstanceOf[ChainComponent[Boolean, Int]]
        pr.solved should be(true)
        c1.subproblems(true).solved should be(true)
        c1.subproblems(false).solved should be(true)

        new LazyDecompositionStrategy(3, pr, defaultRangeSizer, false).execute()

        pr.solved should be(false)
        // The first subproblem was fully refined so its solution should remain; the second subproblem was expanded
        // further so its solution should be removed
        c1.subproblems(true).solved should be(true)
        c1.subproblems(false).solved should be(false)
      }
    }
  }
}
