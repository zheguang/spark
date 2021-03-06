/*
Copyright 2020, Brown University, Providence, RI.

                        All Rights Reserved

Permission to use, copy, modify, and distribute this software and
its documentation for any purpose other than its incorporation into a
commercial product or service is hereby granted without fee, provided
that the above copyright notice appear in all copies and that both
that copyright notice and this permission notice appear in supporting
documentation, and that the name of Brown University not be used in
advertising or publicity pertaining to distribution of the software
without specific, written prior permission.

BROWN UNIVERSITY DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE,
INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR ANY
PARTICULAR PURPOSE.  IN NO EVENT SHALL BROWN UNIVERSITY BE LIABLE FOR
ANY SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.apache.spark.examples.sql.dex
// scalastyle:off

import scala.util.Random

object TPCHPredicatesFilterJoin extends DexTPCHBenchCommon {

  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    val variant = BenchVariant.from(args(0))

    val queries = Seq(
      // F - P_F - f(P)
      BenchQuery("fr1a", "select * from lineitem, orders, customer where l_orderkey = o_orderkey and o_custkey = c_custkey and c_mktsegment = 'BUILDING'",
        lineitem
          .join(orders).where("l_orderkey = o_orderkey")
          .join(customer).where("o_custkey = c_custkey and c_mktsegment = 'BUILDING'")),

      // F - (P_F - f(P))
      BenchQuery("fr1b", "select * from lineitem, orders, customer where o_custkey = c_custkey and (l_orderkey = o_orderkey and c_mktsegment = 'BUILDING')",
        lineitem.join(orders
          .join(customer).where("o_custkey = c_custkey and c_mktsegment = 'BUILDING'")
        ).where("l_orderkey = o_orderkey")),

      // F - (f(P) - P_F)
      BenchQuery("fr1c", "select * from lineitem, orders, customer where c_custkey = o_custkey and (c_mktsegment = 'BUILDING' and l_orderkey = o_orderkey)",
        lineitem.join(customer.where("c_mktsegment = 'BUILDING'")
          .join(orders).where("c_custkey = o_custkey")
        ).where("l_orderkey = o_orderkey")),

      // f(P) - F_P - F, complete linear chain
      BenchQuery("fr1d", "select * from customer, orders, lineitem where c_mktsegment = 'BUILDING' and c_custkey = o_custkey and o_orderkey = l_orderkey",
        customer.where("c_mktsegment = 'BUILDING'")
          .join(orders).where("c_custkey = o_custkey")
          .join(lineitem).where("o_orderkey = l_orderkey")),

      // f(P) - (F_P - F)
      BenchQuery("fr1e", "select * from customer, orders, lineitem where c_mktsegment = 'BUILDING' and (o_orderkey = l_orderkey and c_custkey = o_custkey)",
        customer.where("c_mktsegment = 'BUILDING'")
          .join(orders
            .join(lineitem).where("o_orderkey = l_orderkey")
          ).where("c_custkey = o_custkey")),

      // F - F => F - f(P) - F, small P
      //       F
      //     /
      //   f(P)
      //     \
      //       F
      BenchQuery("ft1a", "select * from supplier, nation, customer where s_nationkey = n_nationkey and n_name = 'FRANCE' and n_nationkey = c_nationkey",
        supplier
          .join(nation).where("s_nationkey = n_nationkey and n_name = 'FRANCE'")
          .join(customer).where("n_nationkey = c_nationkey")),

      // F - F => f(P) - F - F, small P.  Second join P - F chained with first join f(P) - F, sees lots of intermediate data amplified from f(P) by first F, so worse than just P - F
      BenchQuery("ft1b", "select * from nation, supplier, customer where n_name = 'FRANCE' and n_nationkey = s_nationkey and n_nationkey = c_nationkey",
        nation.where("n_name = 'FRANCE'")
          .join(supplier).where("n_nationkey = s_nationkey")
          .join(customer).where("n_nationkey = c_nationkey")),

      // F - F => F - (F - f(P)), small P
      BenchQuery("ft1c", "select * from supplier, nation, customer where s_nationkey = n_nationkey and c_nationkey = n_nationkey and n_name = 'FRANCE'",
        customer
          .join(supplier
            .join(nation).where("s_nationkey = n_nationkey and n_name = 'FRANCE'")
          ).where("c_nationkey = n_nationkey")),

      // F -F => F - (f(P) - F), small P, without filter its the same as t1a, but with filter it's different, one less chaining
      BenchQuery("ft1d", "select * from supplier, nation, customer where s_nationkey = n_nationkey and c_nationkey = n_nationkey and n_name = 'FRANCE'",
        customer
          .join(nation.where("n_name = 'FRANCE'")
            .join(supplier).where("n_nationkey = s_nationkey"))
          .where("c_nationkey = n_nationkey")
      ),

      //   P
      //  /
      // CP
      //  \
      //   P
      // CP - f(P) - P
      BenchQuery("ft2a", "select * from partsupp, part, supplier where ps_partkey = p_partkey and p_size = 15 and ps_suppkey = s_suppkey",
        partsupp
          .join(part).where("ps_partkey = p_partkey and p_size = 15")
          .join(supplier).where("ps_suppkey = s_suppkey")),

      // f(P) - CP - P
      BenchQuery("ft2b", "select * from part, partsupp, supplier where p_size = 15 and p_partkey = ps_partkey and ps_suppkey = s_suppkey",
        part.where("p_size = 15")
          .join(partsupp).where("p_partkey = ps_partkey")
          .join(supplier).where("ps_suppkey = s_suppkey")),

      // P - (f(P) - CP)
      BenchQuery("ft2c", "select * from part, supplier, partsupp where p_partkey = ps_partkey and (p_size = 15 and s_suppkey = ps_suppkey)",
        supplier
          .join(part.where("p_size = 15")
            .join(partsupp).where("p_partkey = ps_partkey")
          ).where("s_suppkey = ps_suppkey")),

      // almost a triangular query. More like R(A, B), S(B, C), T(A, D*).
      //   P
      //  /
      // F
      //  \
      //   P
      // F - f(P) - P
      BenchQuery("ft3a", "select * from lineitem, part, supplier where l_partkey = p_partkey and p_size = 15 and l_suppkey = s_suppkey",
        lineitem
          .join(part).where("l_partkey = p_partkey and p_size = 15")
          .join(supplier).where("l_suppkey = s_suppkey")),

      // f(P) - F - P
      BenchQuery("ft3b", "select * from part, lineitem, supplier where p_size = 15 and p_partkey = l_partkey and l_suppkey = s_suppkey",
        part.where("p_size = 15")
          .join(lineitem).where("p_partkey = l_partkey")
          .join(supplier).where("l_suppkey = s_suppkey")),

      // P - (f(P) - F)
      BenchQuery("ft3c", "select * from part, supplier, lineitem where p_partkey = l_partkey and (p_size = 15 and s_suppkey = l_suppkey)",
        supplier
          .join(part.where("p_size = 15")
            .join(lineitem).where("p_partkey = l_partkey")
          ).where("s_suppkey = l_suppkey")),

      // f(N) - C - O - L: {f(N) - C - O - [L]} join L.  Chain = 3.  Using filtered reuslts in chaining fashion.
      BenchQuery("fq1a",
        """
          |select *
          |from nation, customer, orders, lineitem
          |where
          |  n_name = 'FRANCE' and n_nationkey = c_nationkey and c_custkey = o_custkey and o_orderkey = l_orderkey
        """.stripMargin,
        nation.where("n_name = 'FRANCE'")
          .join(customer).where("n_nationkey = c_nationkey")
          .join(orders).where("c_custkey = o_custkey")
          .join(lineitem).where("o_orderkey = l_orderkey")
      ),

      // f(N) - C - (O - L):  {f(N) - C - [O]} join {O - [L]} - L.  Chain = 2
      BenchQuery("fq1b",
        """
          |select *
          |from nation, customer, orders, lineitem
          |where
          |  n_name = 'FRANCE' and n_nationkey = c_nationkey and c_custkey = o_custkey and o_orderkey = l_orderkey
        """.stripMargin,
        nation.where("n_name = 'FRANCE'")
          .join(customer).where("n_nationkey = c_nationkey")
          .join(orders
            .join(lineitem).where("o_orderkey = l_orderkey"))
          .where("c_custkey = o_custkey")
      ),

      // f(N) - (C - O - L): {f(N) - [C]} join {C - [O]} - O join {O - [L]} - L.  Chain = 1.  Not joining filtered results until late
      BenchQuery("fq1c",
        """
          |select *
          |from nation, customer, orders, lineitem
          |where
          |  n_name = 'FRANCE' and n_nationkey = c_nationkey and c_custkey = o_custkey and o_orderkey = l_orderkey
        """.stripMargin,
        nation.where("n_name = 'FRANCE'")
          .join(customer
            .join(orders).where("c_custkey = o_custkey")
            .join(lineitem).where("o_orderkey = l_orderkey"))
          .where("n_nationkey = c_nationkey")
      ),

      // L - (f(N) - C - O): {L - [O]} join {f(N) - C - [O]} - O, close to fq1b.  Chain = 2
      BenchQuery("fq1d",
        """
          |select *
          |from nation, customer, orders, lineitem
          |where
          |  n_name = 'FRANCE' and n_nationkey = c_nationkey and c_custkey = o_custkey and o_orderkey = l_orderkey
        """.stripMargin,
        lineitem
          .join(nation.where("n_name = 'FRANCE'")
            .join(customer).where("n_nationkey = c_nationkey")
            .join(orders).where("c_custkey = o_custkey")
          ).where("l_orderkey = o_orderkey")
      ),

      // L - O - (f(N) - C): {L - [O]} - O join {O - [C]} join {f(N) - [C}} - C.  Close to fq1c but less intermediate data due to joining filtered subtree.  Chain = 1
      BenchQuery("fq1e",
        """
          |select *
          |from nation, customer, orders, lineitem
          |where
          |  n_name = 'FRANCE' and n_nationkey = c_nationkey and c_custkey = o_custkey and o_orderkey = l_orderkey
        """.stripMargin,
        lineitem
          .join(orders).where("l_orderkey = o_orderkey")
          .join(nation.where("n_name = 'FRANCE'")
            .join(customer).where("n_nationkey = c_nationkey"))
          .where("o_custkey = c_custkey")
      ),

      // L - O - C - f(N): Chain = 0.  Similar to fq1e but purely using join to use filtered resutls.
      BenchQuery("fq1f",
        """
          |select *
          |from nation, customer, orders, lineitem
          |where
          |  n_name = 'FRANCE' and n_nationkey = c_nationkey and c_custkey = o_custkey and o_orderkey = l_orderkey
        """.stripMargin,
        lineitem
          .join(orders).where("l_orderkey = o_orderkey")
          .join(customer).where("o_custkey = c_custkey")
          .join(nation).where("c_nationkey = n_nationkey and n_name = 'FRANCE'")
      ),

      // filter affinity of P_F table
      // F - f(P_F) - P
      BenchQuery("fr2a", "select * from lineitem, orders, customer where l_orderkey = o_orderkey and o_orderstatus = 'F' and o_custkey = c_custkey",
        lineitem
          .join(orders).where("l_orderkey = o_orderkey and o_orderstatus = 'F' ")
          .join(customer).where("o_custkey = c_custkey")
      ),

      // F - (f(P_F) - P), intermediate data
      BenchQuery("fr2b", "select * from lineitem, orders, customer where o_custkey = c_custkey and o_orderstatus = 'F' and l_orderkey = o_orderkey",
        lineitem.join(orders.where("o_orderstatus = 'F' ")
          .join(customer).where("o_custkey = c_custkey")
        ).where("l_orderkey = o_orderkey")
      ),

      // P - f(F_P) - F
      BenchQuery("fr2d", "select * from customer, orders, lineitem where c_custkey = o_custkey and o_orderstatus = 'F' and o_orderkey = l_orderkey",
        customer
          .join(orders).where("c_custkey = o_custkey and o_orderstatus = 'F' ")
          .join(lineitem).where("o_orderkey = l_orderkey")
      ),

      // P - (f(F_P) - F)
      BenchQuery("fr2e", "select * from customer, orders, lineitem where o_orderkey = l_orderkey and o_orderstatus = 'F' and c_custkey = o_custkey",
        customer
          .join(orders.where("o_orderstatus = 'F'")
            .join(lineitem).where("o_orderkey = l_orderkey")
          ).where("c_custkey = o_custkey")
      ),

      // f(F_P) - P
      //        \
      //          F
      BenchQuery("fr2f", "select * from customer, orders, lineitem where o_orderkey = l_orderkey and o_orderstatus = 'F' and c_custkey = o_custkey",
        orders.where("o_orderstatus = 'F'")
          .join(customer).where("o_custkey = c_custkey")
          .join(lineitem).where("o_orderkey = l_orderkey")
      ),

      // snowflake: further filter on dimension P
      // f(C) - O - L - P
      BenchQuery("fs1a",
        """
          |select *
          |from
          |  customer, orders, lineitem, part
          |where
          |  c_mktsegment = 'BUILDING' and c_custkey = o_custkey and o_orderkey = l_orderkey and l_partkey = p_partkey
        """.stripMargin,
        customer.where("c_mktsegment = 'BUILDING'")
          .join(orders).where("c_custkey = o_custkey")
          .join(lineitem).where("o_orderkey = l_orderkey")
          .join(part).where("l_partkey = p_partkey")
      ),

      // P - (L - (f(C) - O))
      BenchQuery("fs1b",
        """
          |select *
          |from
          |  customer, orders, lineitem, part
          |where
          |  c_mktsegment = 'BUILDING' and c_custkey = o_custkey and o_orderkey = l_orderkey and l_partkey = p_partkey
        """.stripMargin,
        part
          .join(lineitem
            .join(customer.where("c_mktsegment = 'BUILDING'")
              .join(orders).where("c_custkey = o_custkey")
            ).where("l_orderkey = o_orderkey")
          ).where("p_partkey = l_partkey")
      ),

      // (P - L) - (f(C) - O)
      BenchQuery("fs1c",
        """
          |select *
          |from
          |  customer, orders, lineitem, part
          |where
          |  c_mktsegment = 'BUILDING' and c_custkey = o_custkey and o_orderkey = l_orderkey and l_partkey = p_partkey
        """.stripMargin,
        part
          .join(lineitem).where("p_partkey = l_partkey")
          .join(customer.where("c_mktsegment = 'BUILDING'")
            .join(orders).where("c_custkey = o_custkey")
          ).where("l_orderkey = o_orderkey")
      )

      // snowflake: closer filter on dimension P

      // snowflake: filter on fact-dimension
    )

    require(queries.map(_.name).toSet.size == queries.size, "unique query name")

    val benchResults = Random.shuffle(queries).map { q =>
      benchQuery(variant, q)
    }

    benchResults.sortBy(_.name).foreach { r =>
      println(s"query=${r.name}, count=${r.resultCount}, duration=${r.duration}")
    }

    spark.stop()
  }
}
