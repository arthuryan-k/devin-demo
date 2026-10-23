package fixgw;

/** The handful of FIX 4.4 tags this gateway knows about. */
@SuppressWarnings("checkstyle:ConstantName")
public interface Tags {
    int Account = 1;
    int AvgPx = 6;
    int BeginSeqNo = 7;
    int BeginString = 8;
    int BodyLength = 9;
    int CheckSum = 10;
    int ClOrdID = 11;
    int CumQty = 14;
    int ExecID = 17;
    int LastPx = 31;
    int LastQty = 32;
    int MsgSeqNum = 34;
    int MsgType = 35;
    int OrderID = 37;
    int OrderQty = 38;
    int OrdStatus = 39;
    int OrdType = 40;
    int OrigClOrdID = 41;
    int Price = 44;
    int RefSeqNum = 45;
    int SenderCompID = 49;
    int SendingTime = 52;
    int Side = 54;
    int Symbol = 55;
    int TargetCompID = 56;
    int Text = 58;
    int TimeInForce = 59;
    int TransactTime = 60;
    int EncryptMethod = 98;
    int HeartBtInt = 108;
    int TestReqID = 112;
    int ExecType = 150;
    int LeavesQty = 151;
    int Username = 553;
    int Password = 554;
}
