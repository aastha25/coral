select `substr`(`r_reason_desc`, 1, 20), avg(`ws_quantity`), avg(`wr_refunded_cash`), avg(`wr_fee`)
from `web_sales`
where `ws_web_page_sk` = `wp_web_page_sk` and `ws_item_sk` = `wr_item_sk` and `ws_order_number` = `wr_order_number` and `ws_sold_date_sk` = `d_date_sk` and `d_year` = 2000 and `cd1`.`cd_demo_sk` = `wr_refunded_cdemo_sk` and `cd2`.`cd_demo_sk` = `wr_returning_cdemo_sk` and `ca_address_sk` = `wr_refunded_addr_sk` and `r_reason_sk` = `wr_reason_sk` and (`cd1`.`cd_marital_status` = 'm' and `cd1`.`cd_marital_status` = `cd2`.`cd_marital_status` and `cd1`.`cd_education_status` = 'advanced degree' and `cd1`.`cd_education_status` = `cd2`.`cd_education_status` and `ws_sales_price` between asymmetric 100.00 and 150.00 or `cd1`.`cd_marital_status` = 's' and `cd1`.`cd_marital_status` = `cd2`.`cd_marital_status` and `cd1`.`cd_education_status` = 'college' and `cd1`.`cd_education_status` = `cd2`.`cd_education_status` and `ws_sales_price` between asymmetric 50.00 and 100.00 or `cd1`.`cd_marital_status` = 'w' and `cd1`.`cd_marital_status` = `cd2`.`cd_marital_status` and `cd1`.`cd_education_status` = '2 yr degree' and `cd1`.`cd_education_status` = `cd2`.`cd_education_status` and `ws_sales_price` between asymmetric 150.00 and 200.00) and (`ca_country` = 'united states' and `ca_state` in ('in', 'oh', 'nj') and `ws_net_profit` between asymmetric 100 and 200 or `ca_country` = 'united states' and `ca_state` in ('wi', 'ct', 'ky') and `ws_net_profit` between asymmetric 150 and 300 or `ca_country` = 'united states' and `ca_state` in ('la', 'ia', 'ar') and `ws_net_profit` between asymmetric 50 and 250)
group by `r_reason_desc`
order by `substr`(`r_reason_desc`, 1, 20), avg(`ws_quantity`), avg(`wr_refunded_cash`), avg(`wr_fee`)
fetch next 100 rows only