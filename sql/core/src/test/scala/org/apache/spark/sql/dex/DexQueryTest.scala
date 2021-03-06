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
package org.apache.spark.sql.dex
// scalastyle:off

import org.apache.spark.sql.catalyst.dex.DexPrimitives.dexTableNameOf
import org.apache.spark.sql.catalyst.dex.DexConstants.{TableAttributeCompound, tCorrJoinName, tDomainName, tFilterName, tUncorrJoinName}

trait DexQueryTest extends DexTest {
  val data2Name = "testdata2"
  val data3Name = "testdata3"
  val data4Name = "testdata4"
  lazy val data2 = spark.read.jdbc(url, data2Name, properties)
  lazy val data3 = spark.read.jdbc(url, data3Name, properties)
  lazy val data4 = spark.read.jdbc(url, data4Name, properties)

  lazy val compoundKeys = Set(
    TableAttributeCompound(data4Name, Seq("e", "f")), TableAttributeCompound(data2Name, Seq("a", "b"))
  )
  lazy val cks = compoundKeys.map(_.attr)

  // Whether to materialize all encrypted test data before the first test is run
  protected def provideEncryptedData: Boolean

  protected override def beforeAll(): Unit = {
    super.beforeAll()

    conn.prepareStatement(s"drop table if exists ${data2Name}").executeUpdate()
    conn.prepareStatement(s"drop table if exists ${data3Name}").executeUpdate()
    conn.prepareStatement(s"drop table if exists ${data4Name}").executeUpdate()
    connEnc.prepareStatement(s"drop table if exists ${dexTableNameOf(data2Name)}").executeUpdate()
    connEnc.prepareStatement(s"drop table if exists ${dexTableNameOf(data3Name)}").executeUpdate()
    connEnc.prepareStatement(s"drop table if exists ${dexTableNameOf(data4Name)}").executeUpdate()
    connEnc.prepareStatement("drop table if exists t_filter").executeUpdate()
    connEnc.prepareStatement("drop table if exists t_correlated_join").executeUpdate()
    connEnc.prepareStatement("drop table if exists t_uncorrelated_join").executeUpdate()
    connEnc.prepareStatement("drop table if exists t_domain").executeUpdate()
    conn.commit()
    connEnc.commit()

    conn.prepareStatement(s"create table ${data2Name} (a int, b int)")
      .executeUpdate()
    conn.prepareStatement(
      s"""
        |insert into ${data2Name} values
        |(1, 1),
        |(1, 2),
        |(2, 1),
        |(2, 2),
        |(3, 1),
        |(3, 2)
      """.stripMargin)
        .executeUpdate()
    conn.commit()

    conn.prepareStatement(s"create table ${data3Name} (c int, d int)")
      .executeUpdate()
    conn.prepareStatement(
      s"""
        |insert into ${data3Name} values
        |(1, 1),
        |(1, 2),
        |(2, 3)
      """.stripMargin)
      .executeUpdate()
    conn.commit()

    conn.prepareStatement(s"create table ${data4Name} (e int, f int, g int, h int)")
      .executeUpdate()
    conn.prepareStatement(
      s"""
        |insert into ${data4Name} values
        |(2, 1, 10, 100),
        |(2, 2, 10, 200),
        |(2, 3, 20, 300),
        |(3, 4, 20, 300),
        |(3, 5, 30, 100)
      """.stripMargin)
      .executeUpdate()
    conn.commit()

    if (provideEncryptedData) {
      // todo: make rid start from 0, to be conssitent with DexBuilder
      connEnc.prepareStatement(s"create table ${dexTableNameOf(data2Name)} (rid varchar, a_prf varchar, b_prf varchar)")
        .executeUpdate()
      connEnc.prepareStatement(
        s"""
          |insert into ${dexTableNameOf(data2Name)} values
          |('1', '1_enc', '1_enc'),
          |('2', '1_enc', '2_enc'),
          |('3', '2_enc', '1_enc'),
          |('4', '2_enc', '2_enc'),
          |('5', '3_enc', '1_enc'),
          |('6', '3_enc', '2_enc')
        """.stripMargin)
        .executeUpdate()
      connEnc.commit()

      connEnc.prepareStatement(s"create table ${dexTableNameOf(data3Name)} (rid varchar, c_prf varchar, d_prf varchar)")
        .executeUpdate()
      connEnc.prepareStatement(
        s"""
          |insert into ${dexTableNameOf(data3Name)} values
          |('1', '1_enc', '1_enc'),
          |('2', '1_enc', '2_enc'),
          |('3', '2_enc', '3_enc')
        """.stripMargin)
        .executeUpdate()
      connEnc.commit()

      connEnc.prepareStatement(s"create table ${dexTableNameOf(data4Name)} (rid varchar, e_prf varchar, f_prf varchar, g_prf varchar, h_prf varchar)")
        .executeUpdate()
      connEnc.prepareStatement(
        s"""
          |insert into ${dexTableNameOf(data4Name)} values
          |('1', '2_enc', '1_enc', '10_enc', '100_enc'),
          |('2', '2_enc', '2_enc', '10_enc', '200_enc'),
          |('3', '2_enc', '3_enc', '20_enc', '300_enc'),
          |('4', '3_enc', '4_enc', '20_enc', '300_enc'),
          |('5', '3_enc', '5_enc', '30_enc', '100_enc')
        """.stripMargin)
        .executeUpdate()
      connEnc.commit()

      // encrypted multi-map of attr -> domain value
      connEnc.prepareStatement(s"create table ${tDomainName}(label varchar, value varchar)")
          .executeUpdate()
      connEnc.prepareStatement(
        s"""
          |insert into ${tDomainName} values
          |('testdata2~a~0', '1_enc'),
          |('testdata2~a~1', '2_enc'),
          |('testdata2~a~2', '3_enc'),
          |
          |('testdata3~c~0', '1_enc'),
          |('testdata3~c~1', '2_enc')
        """.stripMargin)
          .executeUpdate()
      connEnc.commit()

      // encrypted multi-map of (attr, domain value) -> rid
      connEnc.prepareStatement(s"create table ${tFilterName} (label varchar, value varchar)")
        .executeUpdate()
      connEnc.prepareStatement(
        s"""
          |insert into ${tFilterName} values
          |('testdata2~a~1~0', '1_enc'),
          |('testdata2~a~1~1', '2_enc'),
          |('testdata2~a~2~0', '3_enc'),
          |('testdata2~a~2~1', '4_enc'),
          |('testdata2~a~3~0', '5_enc'),
          |('testdata2~a~3~1', '6_enc'),
          |
          |('testdata2~b~1~0', '1_enc'),
          |('testdata2~b~1~1', '3_enc'),
          |('testdata2~b~1~2', '5_enc'),
          |('testdata2~b~2~0', '2_enc'),
          |('testdata2~b~2~1', '4_enc'),
          |('testdata2~b~2~2', '6_enc'),
          |
          |('testdata3~c~1~0', '1_enc'),
          |('testdata3~c~1~1', '2_enc'),
          |('testdata3~c~2~0', '3_enc'),
          |
          |('testdata2~a~b~0', '1_enc'),
          |('testdata2~a~b~1', '4_enc')
        """.stripMargin)
        .executeUpdate()
      connEnc.commit()

      // encrypted multi-map of (attr, attr, rid) -> rid
      connEnc.prepareStatement(s"create table ${tCorrJoinName} (label varchar, value varchar)")
        .executeUpdate()
      connEnc.prepareStatement(
        s"""
          |insert into ${tCorrJoinName} values
          |('testdata2~a~testdata4~e~3~0', '1_enc'),
          |('testdata2~a~testdata4~e~3~1', '2_enc'),
          |('testdata2~a~testdata4~e~3~2', '3_enc'),
          |('testdata2~a~testdata4~e~4~0', '1_enc'),
          |('testdata2~a~testdata4~e~4~1', '2_enc'),
          |('testdata2~a~testdata4~e~4~2', '3_enc'),
          |('testdata2~a~testdata4~e~5~0', '4_enc'),
          |('testdata2~a~testdata4~e~5~1', '5_enc'),
          |('testdata2~a~testdata4~e~6~0', '4_enc'),
          |('testdata2~a~testdata4~e~6~1', '5_enc'),
          |
          |('testdata2~a~testdata3~c~1~0', '1_enc'),
          |('testdata2~a~testdata3~c~1~1', '2_enc'),
          |('testdata2~a~testdata3~c~2~0', '1_enc'),
          |('testdata2~a~testdata3~c~2~1', '2_enc'),
          |('testdata2~a~testdata3~c~3~0', '3_enc'),
          |('testdata2~a~testdata3~c~4~0', '3_enc'),
          |
          |('testdata2~b~testdata3~c~1~0', '1_enc'),
          |('testdata2~b~testdata3~c~1~1', '2_enc'),
          |('testdata2~b~testdata3~c~2~0', '3_enc'),
          |('testdata2~b~testdata3~c~3~0', '1_enc'),
          |('testdata2~b~testdata3~c~3~1', '2_enc'),
          |('testdata2~b~testdata3~c~4~0', '3_enc'),
          |('testdata2~b~testdata3~c~5~0', '1_enc'),
          |('testdata2~b~testdata3~c~5~1', '2_enc'),
          |('testdata2~b~testdata3~c~6~0', '3_enc'),
          |
          |('testdata2~b~testdata3~d~1~0', '1_enc'),
          |('testdata2~b~testdata3~d~2~0', '2_enc'),
          |('testdata2~b~testdata3~d~3~0', '1_enc'),
          |('testdata2~b~testdata3~d~4~0', '2_enc'),
          |('testdata2~b~testdata3~d~5~0', '1_enc'),
          |('testdata2~b~testdata3~d~6~0', '2_enc'),
          |
          |('testdata3~c~testdata3~c~1~0', '1_enc'),
          |('testdata3~c~testdata3~c~1~1', '2_enc'),
          |('testdata3~c~testdata3~c~2~0', '1_enc'),
          |('testdata3~c~testdata3~c~2~1', '2_enc'),
          |('testdata3~c~testdata3~c~3~0', '3_enc'),
          |
          |('testdata3~c~testdata4~e~3~0', '1_enc'),
          |('testdata3~c~testdata4~e~3~1', '2_enc'),
          |('testdata3~c~testdata4~e~3~2', '3_enc'),
          |
          |('testdata4~e_and_f~testdata2~a_and_b~1~0','3_enc'),
          |('testdata4~e_and_f~testdata2~a_and_b~2~0','4_enc'),
          |('testdata2~a_and_b~testdata4~e_and_f~4~0','2_enc'),
          |('testdata2~a_and_b~testdata4~e_and_f~3~0','1_enc')
        """.stripMargin)
        .executeUpdate()
      connEnc.commit()

      // encrypted multi-map of (attr, attr) -> (rid, rid)
      connEnc.prepareStatement(s"create table ${tUncorrJoinName} (label varchar, value_left varchar, value_right varchar)")
        .executeUpdate()
      connEnc.prepareStatement(
        s"""
          |insert into ${tUncorrJoinName} values
          |('testdata2~a~testdata4~e~0', '3_enc', '1_enc'),
          |('testdata2~a~testdata4~e~1', '3_enc', '2_enc'),
          |('testdata2~a~testdata4~e~2', '3_enc', '3_enc'),
          |('testdata2~a~testdata4~e~3', '4_enc', '1_enc'),
          |('testdata2~a~testdata4~e~4', '4_enc', '2_enc'),
          |('testdata2~a~testdata4~e~5', '4_enc', '3_enc'),
          |('testdata2~a~testdata4~e~6', '5_enc', '4_enc'),
          |('testdata2~a~testdata4~e~7', '5_enc', '5_enc'),
          |('testdata2~a~testdata4~e~8', '6_enc', '4_enc'),
          |('testdata2~a~testdata4~e~9', '6_enc', '5_enc'),
          |
          |('testdata2~a~testdata3~c~0', '1_enc', '1_enc'),
          |('testdata2~a~testdata3~c~1', '1_enc', '2_enc'),
          |('testdata2~a~testdata3~c~2', '2_enc', '1_enc'),
          |('testdata2~a~testdata3~c~3', '2_enc', '2_enc'),
          |('testdata2~a~testdata3~c~4', '3_enc', '3_enc'),
          |('testdata2~a~testdata3~c~5', '4_enc', '3_enc'),
          |
          |('testdata2~b~testdata3~c~0', '1_enc', '1_enc'),
          |('testdata2~b~testdata3~c~1', '1_enc', '2_enc'),
          |('testdata2~b~testdata3~c~2', '2_enc', '3_enc'),
          |('testdata2~b~testdata3~c~3', '3_enc', '1_enc'),
          |('testdata2~b~testdata3~c~4', '3_enc', '2_enc'),
          |('testdata2~b~testdata3~c~5', '4_enc', '3_enc'),
          |('testdata2~b~testdata3~c~6', '5_enc', '1_enc'),
          |('testdata2~b~testdata3~c~7', '5_enc', '2_enc'),
          |('testdata2~b~testdata3~c~8', '6_enc', '3_enc'),
          |
          |('testdata2~b~testdata3~d~0', '1_enc', '1_enc'),
          |('testdata2~b~testdata3~d~1', '2_enc', '2_enc'),
          |('testdata2~b~testdata3~d~2', '3_enc', '1_enc'),
          |('testdata2~b~testdata3~d~3', '4_enc', '2_enc'),
          |('testdata2~b~testdata3~d~4', '5_enc', '1_enc'),
          |('testdata2~b~testdata3~d~5', '6_enc', '2_enc')
        """.stripMargin
      ).executeUpdate()
      connEnc.commit()
    }
  }
}
