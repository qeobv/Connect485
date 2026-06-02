package org.example;

public class ModbusProtocolMapTest {

    public static void main(String[] args) {
        System.out.println("========== Modbus 协议映射表 单元测试 ==========\n");

        // 1. 测试 INT 类型 (模拟量输入)
        testIntType();

        // 2. 测试 ENUM_INT 类型 (枚举映射)
        testEnumType();

        // 3. 测试 BIT 类型 (数字量输入/输出 - 位操作)
        testBitType();

        // 4. 测试异常与边界情况 (未知地址、越界枚举)
        testErrorCases();

        System.out.println("\n========== 测试执行完毕 ==========");
    }

    private static void testIntType() {
        System.out.println("--- 1. INT 类型测试 (模拟量) ---");
        // 地址6: 阀门当前开度值 (量程0-1000 ‰)
        System.out.println(ModbusProtocolMap.translate(6, 500));
        // 地址9: 电机工作电流 (量程0-60000 mA)
        System.out.println(ModbusProtocolMap.translate(9, 1250));
        System.out.println();
    }

    private static void testEnumType() {
        System.out.println("--- 2. ENUM_INT 类型测试 (控制方式/信号类型) ---");
        // 地址7: 读取当前控制方式 -> 6对应Modbus
        System.out.println(ModbusProtocolMap.translate(7, 6));
        // 地址7: 读取当前控制方式 -> 0对应就地
        System.out.println(ModbusProtocolMap.translate(7, 0));
        // 地址13: 设定远程信号类型 -> 4对应二线制常开
        System.out.println(ModbusProtocolMap.translate(13, 4));
        System.out.println();
    }

    private static void testBitType() {
        System.out.println("--- 3. BIT 类型测试 (状态量/位操作) ---");
        // 模拟地址1收到的16位寄存器值，其二进制为: 0000 0000 0000 0101 (第0位和第2位为1)
        // 即：阀开到位=1, 阀关到位=0, 阀到指定位置=1
        int rawAddr1 = 0b0000000000000101; // 十进制 5

        System.out.println(ModbusProtocolMap.translate(1, rawAddr1)); // 阀开到位: ✔ 是
        System.out.println(ModbusProtocolMap.translate(1, rawAddr1)); // 阀关到位: ✖ 否 (这里故意用同一个rawAddr1测不同位)

        // 我们需要通过不同的bitPos来解析同一个rawAddr1，但你的API是translate(address, rawValue)
        // 所以为了测试阀关到位(bit1)，我们传入一个bit1为1的值: 0000 0000 0000 0010 (十进制2)
        System.out.println(ModbusProtocolMap.translate(1, 2));       // 阀关到位: ✔ 是

        // 模拟地址3收到的值，包含多个故障信息
        // 二进制: 0000 0010 0001 1010
        // bit1(相序异常)=1, bit3(C相断线)=1, bit4(电机过温)=1, bit9(开关拒动)=1
        int rawAddr3 = 0b0000001000011010; // 十进制 538
        System.out.println(ModbusProtocolMap.translate(3, rawAddr3)); // 相序异常: ✔ 是
        System.out.println(ModbusProtocolMap.translate(3, rawAddr3)); // 4-20mA无输入: ✖ 否
        System.out.println();
    }

    private static void testErrorCases() {
        System.out.println("--- 4. 异常与边界测试 ---");
        // 4.1 测试未定义的寄存器地址 (如地址 999)
        System.out.println(ModbusProtocolMap.translate(999, 100));

        // 4.2 测试枚举越界 (控制方式只有0-6，传入9)
        System.out.println(ModbusProtocolMap.translate(7, 9));

        // 4.3 测试INT类型超出理论量程 (代码未做截断，原样输出，这是符合Modbus原始数据展示逻辑的)
        System.out.println(ModbusProtocolMap.translate(6, 1200)); // 开度值量程0-1000，传入1200
        System.out.println();
    }
}
